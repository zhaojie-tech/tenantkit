package io.tenantkit.core.async;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Async propagation contract (DESIGN §6.3): decorator, wrap(), clear-both. */
class AsyncPropagationTest {

    @AfterEach
    void clean() {
        MDC.clear();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void decoratorCarriesTenantAndMdcToWorkerAndClearsAfterwards() throws Exception {
        TenantPropagationTaskDecorator decorator = new TenantPropagationTaskDecorator();
        AtomicReference<TenantId> seenTenant = new AtomicReference<>();
        AtomicReference<String> seenMdc = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // production parity: the resolution filter writes the MDC before hand-off
        MDC.put(TenantMdc.TENANT_KEY, "alpha");

        AtomicReference<Runnable> decoratedRef = new AtomicReference<>();
        TenantContext.runAs(TenantId.of("alpha"), () -> decoratedRef.set(decorator.decorate(() -> {
            seenTenant.set(TenantContext.currentOrNull());
            seenMdc.set(MDC.get(TenantMdc.TENANT_KEY));
            MDC.put(TenantMdc.TENANT_KEY, "mutated-by-task");
            done.countDown();
        })));
        new Thread(decoratedRef.get()).start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenTenant.get()).isEqualTo(TenantId.of("alpha"));
        assertThat(seenMdc.get()).isEqualTo("alpha");
        assertThat(TenantContext.currentOrNull()).as("caller thread has no tenant").isNull();
        assertThat(MDC.get(TenantMdc.TENANT_KEY)).as("caller thread MDC untouched by 双清").isEqualTo("alpha");
    }

    @Test
    void decoratorWithoutTenantLeavesContextEmptyAndRestoresCallerMdc() throws Exception {
        TenantPropagationTaskDecorator decorator = new TenantPropagationTaskDecorator();
        MDC.put("other", "value");
        Map<String, String> callerMdc = TenantMdc.capture();
        CountDownLatch done = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(() -> {
            MDC.put(TenantMdc.TENANT_KEY, "x");
            done.countDown();
        });
        new Thread(decorated).start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(TenantContext.currentOrNull()).isNull();
        Map<String, String> now = MDC.getCopyOfContextMap();
        assertThat(now == null ? Map.of() : now).as("caller MDC state restored").isEqualTo(callerMdc);
    }

    @Test
    void taskExceptionStillClearsBoth() {
        TenantPropagationTaskDecorator decorator = new TenantPropagationTaskDecorator();
        AtomicReference<Runnable> decoratedRef = new AtomicReference<>();
        TenantContext.runAs(TenantId.of("alpha"), () -> decoratedRef.set(decorator.decorate(() -> {
            throw new IllegalStateException("boom");
        })));
        assertThatThrownBy(decoratedRef.get()::run).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.currentOrNull()).isNull();
        assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
    }

    @Test
    void wrappedExecutorCarriesTenantAcrossThreads() throws Exception {
        ExecutorService wrapped = TenantExecutors.wrap(Executors.newSingleThreadExecutor());
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            TenantContext.runAs(TenantId.of("beta"), () -> wrapped.execute(() -> {
                seen.set(TenantContext.currentOrNull());
                done.countDown();
            }));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isEqualTo(TenantId.of("beta"));
        }
        finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void wrappedExecutorServiceSubmitCarriesTenant() throws Exception {
        ExecutorService wrapped = TenantExecutors.wrap(Executors.newSingleThreadExecutor());
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            TenantContext.runAs(TenantId.of("beta"), () -> {
                try {
                    seen.set(wrapped.submit(TenantContext::required).get(5, TimeUnit.SECONDS));
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            assertThat(seen.get()).isEqualTo(TenantId.of("beta"));
        }
        finally {
            wrapped.shutdownNow();
        }
    }

    /** R4 P2 fix lock-in: pool reuse must not leak the first task's extra MDC keys to the next raw submit. */
    @Test
    void poolReuseDoesNotLeakCallersMdcKeysToUnwrappedWork() throws Exception {
        java.util.concurrent.ExecutorService raw = Executors.newSingleThreadExecutor();
        ExecutorService wrapped = TenantExecutors.wrap(raw);
        try {
            CountDownLatch firstDone = new CountDownLatch(1);
            MDC.put(TenantMdc.TENANT_KEY, "alpha");
            MDC.put("traceId", "trace-A");
            TenantContext.runAs(TenantId.of("alpha"), () -> wrapped.execute(() -> {
                firstDone.countDown();
            }));
            assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
            MDC.clear();

            // raw submit (not wrapped): the thread must carry NO residue from the wrapped task
            AtomicReference<Map<String, String>> seenMdc = new AtomicReference<>();
            CountDownLatch secondDone = new CountDownLatch(1);
            raw.execute(() -> {
                seenMdc.set(MDC.getCopyOfContextMap() == null ? Map.of() : MDC.getCopyOfContextMap());
                secondDone.countDown();
            });
            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seenMdc.get()).as("worker MDC must be restored to its pre-task state").isEmpty();
        }
        finally {
            wrapped.shutdownNow();
        }
    }

    /** R4: the raw-interface wrap(Executor) overload is a primary documented path — cover it. */
    @Test
    void wrappedRawExecutorInterfacePropagatesTenant() throws Exception {
        java.util.concurrent.ExecutorService backing = Executors.newSingleThreadExecutor();
        java.util.concurrent.Executor wrapped = TenantExecutors.wrap((java.util.concurrent.Executor) backing);
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            TenantContext.runAs(TenantId.of("delta"), () -> wrapped.execute(() -> {
                seen.set(TenantContext.currentOrNull());
                done.countDown();
            }));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isEqualTo(TenantId.of("delta"));
        }
        finally {
            backing.shutdownNow();
        }
    }

    /** R4: executor-service lifecycle delegates are part of the wrapper contract. */
    @Test
    void wrappedExecutorServiceLifecycleDelegates() throws Exception {
        ExecutorService wrapped = TenantExecutors.wrap(Executors.newSingleThreadExecutor());
        assertThat(wrapped.isShutdown()).isFalse();
        assertThat(wrapped.isTerminated()).isFalse();
        wrapped.shutdown();
        assertThat(wrapped.isShutdown()).isTrue();
        assertThat(wrapped.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    /** R4: submit with no bound tenant takes the pass-through branch. */
    @Test
    void wrappedSubmitWithoutTenantPassesThrough() throws Exception {
        ExecutorService wrapped = TenantExecutors.wrap(Executors.newSingleThreadExecutor());
        try {
            java.util.concurrent.Future<String> future = wrapped.submit(() -> "plain");
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("plain");
        }
        finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void wrappedExecutorWithoutTenantKeepsWorkerClean() throws Exception {
        ExecutorService wrapped = TenantExecutors.wrap(Executors.newSingleThreadExecutor());
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            wrapped.execute(() -> {
                seen.set(TenantContext.currentOrNull());
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isNull();
        }
        finally {
            wrapped.shutdownNow();
        }
    }
}
