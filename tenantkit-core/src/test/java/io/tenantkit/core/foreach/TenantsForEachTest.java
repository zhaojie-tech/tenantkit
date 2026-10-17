package io.tenantkit.core.foreach;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.catalog.ConfigTenantCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tenants.forEach contract (DESIGN §6.6). */
class TenantsForEachTest {

    private final TenantCatalog catalog = new ConfigTenantCatalog(
            List.of(TenantId.of("alpha"), TenantId.of("beta")));

    @AfterEach
    void clearAmbientCatalog() {
        Tenants.clearCatalog();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void runsEachTenantScopedAndReportsSuccess() {
        AtomicReference<String> seen = new AtomicReference<>();
        ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> {
            assertThat(tenant).isSameAs(id);
            seen.set(TenantContext.required().value());
        }, ForEachOptions.defaults());

        assertThat(report.allSucceeded()).isTrue();
        assertThat(report.perTenant()).extracting(r -> r.tenant().value())
                .containsExactly("alpha", "beta");
        assertThat(seen.get()).isEqualTo("beta");
    }

    @Test
    void ambientCatalogOverloadWorksWhenRegistered() {
        Tenants.useCatalog(catalog);
        ForEachReport report = Tenants.forEach((id, tenant) -> { }, ForEachOptions.defaults());
        assertThat(report.perTenant()).hasSize(2);
    }

    @Test
    void ambientCatalogMissingFailsWithClearMessage() {
        assertThatThrownBy(() -> Tenants.forEach((id, t) -> { }, ForEachOptions.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantCatalog");
    }

    @Test
    void failureIsIsolatedAndAggregatedByDefault() {
        ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> {
            if (id.value().equals("alpha")) {
                throw new IllegalStateException("alpha is down");
            }
        }, ForEachOptions.defaults());

        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.perTenant().get(0).success()).isFalse();
        assertThat(report.perTenant().get(0).failure()).isInstanceOf(IllegalStateException.class);
        assertThat(report.perTenant().get(1).success()).isTrue();
    }

    @Test
    void continueOnErrorFalseAbortsBatchAndRethrows() {
        AtomicBoolean betaRan = new AtomicBoolean(false);
        assertThatThrownBy(() -> Tenants.forEach(catalog, (id, tenant) -> {
            if (id.value().equals("alpha")) {
                throw new IllegalStateException("alpha is down");
            }
            betaRan.set(true);
        }, new ForEachOptions(null, null, false, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha is down");
        assertThat(betaRan).as("batch must abort at the first failure").isFalse();
    }

    @Test
    void perTenantTimeoutIsolatesSlowTenant() {
        ForEachOptions options = new ForEachOptions(Duration.ofMillis(150), null, true, false);
        ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> {
            if (id.value().equals("alpha")) {
                try {
                    Thread.sleep(2000);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, options);

        assertThat(report.perTenant().get(0).success()).isFalse();
        assertThat(report.perTenant().get(0).failure()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(report.perTenant().get(1).success()).isTrue();
    }

    @Test
    void perTenantTimeoutUsesProvidedExecutor() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ForEachOptions options = new ForEachOptions(Duration.ofSeconds(2), pool, true, false);
            ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> { }, options);
            assertThat(report.allSucceeded()).isTrue();
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outerTransactionWarnsAndStillRuns() {
        AtomicBoolean ran = new AtomicBoolean(false);
        ForEachReport report = inTransaction(
                () -> Tenants.forEach(catalog, (id, t) -> ran.set(true), ForEachOptions.defaults()));
        assertThat(ran).isTrue();
        assertThat(report.allSucceeded()).isTrue();
    }

    @Test
    void outerTransactionThrowsWhenConfiguredFail() {
        assertThatThrownBy(() -> inTransaction(() -> Tenants.forEach(catalog, (id, t) -> { },
                new ForEachOptions(null, null, true, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    /** R4 P2 fix lock-in: a rejecting user executor must not abort the batch under continueOnError=true. */
    @Test
    void rejectedExecutionIsAggregatedNotFatal() {
        java.util.concurrent.Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
        ForEachOptions options = new ForEachOptions(Duration.ofSeconds(1), rejecting, true, false);
        ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> { }, options);
        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.perTenant()).allSatisfy(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.failure())
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        });
    }

    /** continueOnError=false keeps its abort semantics for rejections too. */
    @Test
    void rejectedExecutionAbortsWhenConfiguredFail() {
        java.util.concurrent.Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
        ForEachOptions options = new ForEachOptions(Duration.ofSeconds(1), rejecting, false, false);
        assertThatThrownBy(() -> Tenants.forEach(catalog, (id, tenant) -> { }, options))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    /** R4 P2: catalog replacement is observable, not silent. */
    @Test
    void catalogReplacementIsLogged() {
        Tenants.useCatalog(catalog);
        TenantCatalog other = new ConfigTenantCatalog(List.of(TenantId.of("gamma")));
        Tenants.useCatalog(other);
        assertThat(Tenants.forEach((id, t) -> { }, ForEachOptions.defaults()).perTenant())
                .extracting(r -> r.tenant().value()).containsExactly("gamma");
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        PlatformTransactionManager tm = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                // minimal managers never flip the actual-transaction flag; do it explicitly
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .setActualTransactionActive(true);
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .setActualTransactionActive(false);
            }

            @Override
            public void rollback(TransactionStatus status) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .setActualTransactionActive(false);
            }
        };
        return new TransactionTemplate(tm).execute(status -> work.get());
    }
}
