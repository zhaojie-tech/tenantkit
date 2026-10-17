/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.core.foreach;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;

/**
 * Runs an action once per tenant from a {@link TenantCatalog}, each run
 * explicitly scoped via {@code TenantContext.runAs} (DESIGN &sect;6.6).
 *
 * <p>Defaults are serial, timeout-less, failure-isolating (a failing tenant is
 * recorded in the {@link ForEachReport} and the batch continues) and WARN on an
 * active outer transaction. "A slow tenant must not drag the whole batch" holds
 * only with an explicit {@code perTenantTimeout}.
 *
 * <p>forEach never opens a transaction of its own; running it inside an outer
 * transaction packs every tenant into one rollback unit and is flagged (or,
 * with {@code failOnOuterTransaction}, rejected). Detection runs only when
 * spring-tx is on the classpath; without it there is no detection (matrix K02).
 *
 * <p>With a configured timeout each tenant runs on the given executor (or an
 * internal bounded daemon executor); the tenant scope is established on the
 * worker thread itself, so no cross-thread propagation is required for this
 * path. The timeout is best-effort cancellation ({@code cancel(true)}): a task
 * that ignores interrupts may keep running after being reported failed, and
 * interrupt-flag cleanup is the executor's responsibility (ThreadPoolExecutor
 * clears it between tasks; hand-rolled executors may not).
 */
public final class Tenants {

    private static final Logger log = LoggerFactory.getLogger(Tenants.class);

    private static final java.util.concurrent.atomic.AtomicInteger THREAD_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static volatile TenantCatalog defaultCatalog;

    private Tenants() {
    }

    /**
     * Registers the ambient catalog used by the two-argument
     * {@link #forEach(BiConsumer, ForEachOptions)}. The TenantKit
     * auto-configuration calls this once at startup when a catalog bean exists.
     */
    public static void useCatalog(TenantCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        TenantCatalog previous = defaultCatalog;
        defaultCatalog = catalog;
        if (previous != null && previous != catalog) {
            // visibility for the multi-context case: last-writer wins for devtools
            // and test rebuilds, but the overwrite must not be silent (R4 P2)
            log.info("TenantKit ambient TenantCatalog replaced: {} -> {}",
                    previous.getClass().getName(), catalog.getClass().getName());
        }
    }

    /** Clears the ambient catalog (test hook). */
    public static void clearCatalog() {
        defaultCatalog = null;
    }

    /**
     * Runs {@code action} for every tenant of the ambient catalog. See
     * {@link #forEach(TenantCatalog, BiConsumer, ForEachOptions)}.
     *
     * @throws IllegalStateException when no ambient catalog is registered
     */
    public static ForEachReport forEach(BiConsumer<TenantId, TenantId> action, ForEachOptions options) {
        TenantCatalog catalog = defaultCatalog;
        if (catalog == null) {
            throw new IllegalStateException("no TenantCatalog registered; configure tenantkit.tenants "
                    + "or register a TenantCatalog bean, or pass a catalog to forEach explicitly");
        }
        return forEach(catalog, action, options);
    }

    /**
     * Runs {@code action} once per tenant of {@code catalog}. The second
     * consumer argument is the tenant currently scoped (same id, for readable
     * {@code (id, tenant) -> ...} lambdas).
     *
     * @return per-tenant outcomes, in catalog order
     */
    public static ForEachReport forEach(TenantCatalog catalog, BiConsumer<TenantId, TenantId> action,
            ForEachOptions options) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(options, "options must not be null");

        guardOuterTransaction(options);

        List<TenantId> tenants = catalog.activeTenants();
        List<TenantRunResult> results = new ArrayList<>(tenants.size());
        boolean timed = options.perTenantTimeout() != null;
        Executor executor = null;
        ExecutorService ownedExecutor = null;
        try {
            if (timed) {
                if (options.executor() != null) {
                    executor = options.executor();
                }
                else {
                    // bounded pool: one thread per in-flight tenant run, capped so a
                    // permanently hung tenant cannot grow threads without bound (R4 P2);
                    // a pure single-thread executor would queue every later tenant
                    // behind the hung one and time the batch out tenant by tenant
                    int maxThreads = Math.max(1, Math.min(tenants.size(), 16));
                    ownedExecutor = new java.util.concurrent.ThreadPoolExecutor(1, maxThreads,
                            60L, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(),
                            runnable -> {
                                Thread thread = new Thread(runnable, "tenantkit-foreach-" + THREAD_SEQ.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            },
                            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
                    executor = ownedExecutor;
                }
            }
            for (TenantId tenant : tenants) {
                results.add(runTenant(tenant, action, options, executor));
            }
        }
        finally {
            if (ownedExecutor != null) {
                ownedExecutor.shutdownNow();
            }
        }
        return new ForEachReport(results);
    }

    private static TenantRunResult runTenant(TenantId tenant, BiConsumer<TenantId, TenantId> action,
            ForEachOptions options, Executor executor) {
        long start = System.nanoTime();
        if (executor == null) {
            try {
                TenantContext.runAs(tenant, () -> action.accept(tenant, tenant));
                return new TenantRunResult(tenant, true, null, elapsed(start));
            }
            catch (RuntimeException ex) {
                return failureOrThrow(options, tenant, ex, start);
            }
        }
        Callable<Void> scoped = () -> {
            TenantContext.runAs(tenant, () -> action.accept(tenant, tenant));
            return null;
        };
        FutureTask<Void> future = new FutureTask<>(scoped);
        try {
            try {
                executor.execute(future);
            }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                // a full/shut-down user executor must not abort the batch when
                // continueOnError=true promises isolation (R4 P2)
                return failureOrThrow(options, tenant, rejected, start);
            }
            future.get(options.perTenantTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new TenantRunResult(tenant, true, null, elapsed(start));
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            return failureOrThrow(options, tenant, ex, start);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof Exception exception) {
                return failureOrThrow(options, tenant, exception, start);
            }
            return failureOrThrow(options, tenant, cause, start);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failureOrThrow(options, tenant, ex, start);
        }
    }

    private static TenantRunResult failureOrThrow(ForEachOptions options, TenantId tenant, Throwable failure,
            long startNanos) {
        if (!options.continueOnError()) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("tenant run failed for " + tenant.value(), failure);
        }
        return new TenantRunResult(tenant, false, failure, elapsed(startNanos));
    }

    private static void guardOuterTransaction(ForEachOptions options) {
        ClassLoader loader = Tenants.class.getClassLoader();
        if (!SpringTxOuterTransactionDetector.isPresent(loader)) {
            return;
        }
        if (SpringTxOuterTransactionDetector.outerTransactionActive()) {
            if (options.failOnOuterTransaction()) {
                throw new IllegalStateException(
                        "Tenants.forEach must not run inside an active transaction: every tenant would share "
                                + "one rollback unit. Open the transaction per tenant inside the action instead.");
            }
            log.warn("Tenants.forEach detected an active outer transaction; all tenants run inside one "
                    + "rollback unit. Configure failOnOuterTransaction=true to reject this, or scope "
                    + "transactions per tenant inside the action.");
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
