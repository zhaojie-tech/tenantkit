package io.tenantkit.starter.async;

import java.util.concurrent.atomic.AtomicReference;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** BPP hooks BEFORE initialization (R1): the decorator must reach the executor before its pool builds. */
class TenantExecutorBeanPostProcessorTest {

    @Test
    void applicationTaskExecutorIsAlwaysDecorated() {
        TenantExecutorBeanPostProcessor bpp = new TenantExecutorBeanPostProcessor(false);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        Object processed = bpp.postProcessBeforeInitialization(executor, "applicationTaskExecutor");
        executor.afterPropertiesSet();
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            TenantId tenant = TenantId.of("alpha");
            TenantContext.runAs(tenant, () -> submitAndWait(executor, seen));
            assertThat(seen.get()).isEqualTo(tenant);
            assertThat(processed).isSameAs(executor);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void userPoolNotDecoratedByDefault() {
        TenantExecutorBeanPostProcessor bpp = new TenantExecutorBeanPostProcessor(false);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        bpp.postProcessBeforeInitialization(executor, "userPool");
        executor.afterPropertiesSet();
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            TenantId tenant = TenantId.of("alpha");
            TenantContext.runAs(tenant, () -> submitAndWait(executor, seen));
            assertThat(seen.get()).as("decorate-user-pools=false keeps user pools untouched").isNull();
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void userPoolDecoratedWhenSwitchedOn() {
        TenantExecutorBeanPostProcessor bpp = new TenantExecutorBeanPostProcessor(true);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        bpp.postProcessBeforeInitialization(executor, "userPool");
        executor.afterPropertiesSet();
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            TenantId tenant = TenantId.of("beta");
            TenantContext.runAs(tenant, () -> submitAndWait(executor, seen));
            assertThat(seen.get()).isEqualTo(tenant);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void simpleAsyncTaskExecutorFollowsTheSameSwitch() {
        TenantExecutorBeanPostProcessor bpp = new TenantExecutorBeanPostProcessor(true);
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        bpp.postProcessBeforeInitialization(executor, "someExecutor");
        AtomicReference<TenantId> seen = new AtomicReference<>();
        TenantId tenant = TenantId.of("gamma");
        TenantContext.runAs(tenant, () -> {
            try {
                executor.submit(() -> seen.set(TenantContext.currentOrNull())).get();
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(seen.get()).isEqualTo(tenant);
    }

    /** R4 P2 fix lock-in: a user TaskDecorator bean is composed, not replaced. */
    @Test
    void userTaskDecoratorIsComposedNotLost() throws Exception {
        java.util.concurrent.atomic.AtomicInteger userRuns = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.core.task.TaskDecorator user = runnable -> () -> {
            userRuns.incrementAndGet();
            runnable.run();
        };
        org.springframework.beans.factory.ObjectProvider<org.springframework.core.task.TaskDecorator> provider =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(org.springframework.core.task.TaskDecorator.class) == null ? null : null;
        // construct a provider that yields exactly one decorator
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("userDecorator", user);
        TenantExecutorBeanPostProcessor bpp =
                new TenantExecutorBeanPostProcessor(true, factory.getBeanProvider(org.springframework.core.task.TaskDecorator.class));

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        bpp.postProcessBeforeInitialization(executor, "userPool");
        executor.afterPropertiesSet();
        try {
            AtomicReference<TenantId> seen = new AtomicReference<>();
            TenantId tenant = TenantId.of("zeta");
            TenantContext.runAs(tenant, () -> submitAndWait(executor, seen));
            assertThat(seen.get()).isEqualTo(tenant);
            assertThat(userRuns.get()).as("user decorator must still run").isEqualTo(1);
        }
        finally {
            executor.shutdown();
        }
    }

    /** R4 P2 fix lock-in: two instances under the same bean name are BOTH decorated. */
    @Test
    void sameBeanNameSecondInstanceIsAlsoDecorated() {
        TenantExecutorBeanPostProcessor bpp = new TenantExecutorBeanPostProcessor(true);
        ThreadPoolTaskExecutor first = new ThreadPoolTaskExecutor();
        ThreadPoolTaskExecutor second = new ThreadPoolTaskExecutor();
        bpp.postProcessBeforeInitialization(first, "sharedName");
        bpp.postProcessBeforeInitialization(second, "sharedName");
        first.afterPropertiesSet();
        second.afterPropertiesSet();
        try {
            AtomicReference<TenantId> seenFirst = new AtomicReference<>();
            AtomicReference<TenantId> seenSecond = new AtomicReference<>();
            TenantId tenant = TenantId.of("omega");
            TenantContext.runAs(tenant, () -> submitAndWait(first, seenFirst));
            TenantContext.runAs(tenant, () -> submitAndWait(second, seenSecond));
            assertThat(seenFirst.get()).isEqualTo(tenant);
            assertThat(seenSecond.get()).as("second instance under the same bean name must be decorated too")
                    .isEqualTo(tenant);
        }
        finally {
            first.shutdown();
            second.shutdown();
        }
    }

    private void submitAndWait(TaskExecutor executor, AtomicReference<TenantId> seen) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            seen.set(TenantContext.currentOrNull());
            done.countDown();
        });
        try {
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("task never ran");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
