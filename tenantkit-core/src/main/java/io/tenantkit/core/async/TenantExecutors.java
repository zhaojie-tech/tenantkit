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
package io.tenantkit.core.async;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;

/**
 * Explicit propagation for executors the kit does not manage: raw
 * {@code ExecutorService} beans and {@code ForkJoinPool.commonPool} (the
 * default behind parallelStream and unconfigured CompletableFuture) are
 * outside the task-decorator coverage boundary, and this is their documented
 * opt-in path (DESIGN &sect;6.3).
 */
public final class TenantExecutors {

    private TenantExecutors() {
    }

    /** Wraps {@code executor} so every submitted task carries tenant + MDC. */
    public static Executor wrap(Executor executor) {
        return new PropagatingExecutor(executor);
    }

    /** Wraps {@code executorService} so submitted tasks carry tenant + MDC. */
    public static ExecutorService wrap(ExecutorService executorService) {
        return new PropagatingExecutorService(executorService);
    }

    static Runnable decorate(TenantId tenant, Runnable task) {
        // MDC travels too: the wrap() javadoc (and TenantMdc) promise it, and
        // tenant-less log lines on worker threads defeat the whole point
        java.util.Map<String, String> mdc = TenantMdc.capture();
        return () -> {
            // snapshot the worker's own state first: restoring it on exit is
            // nested-safe (an inline/synchronous delegate runs inside an outer
            // scope) and cannot leave the submitting task's keys behind on a
            // reused pool thread (R4 P2: remove(tenantId)-only leaked traceId)
            java.util.Map<String, String> workerBefore = TenantMdc.capture();
            TenantMdc.restore(mdc);
            try {
                if (tenant != null) {
                    TenantContext.runAs(tenant, task);
                }
                else {
                    task.run();
                }
            }
            finally {
                TenantMdc.restore(workerBefore);
            }
        };
    }

    private record PropagatingExecutor(Executor delegate) implements Executor {

        @Override
        public void execute(Runnable command) {
            delegate.execute(decorate(TenantContext.currentOrNull(), command));
        }
    }

    private static final class PropagatingExecutorService
            extends java.util.concurrent.AbstractExecutorService {

        private final ExecutorService delegate;

        PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(decorate(TenantContext.currentOrNull(), command));
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            TenantId tenant = TenantContext.currentOrNull();
            java.util.Map<String, String> mdc = TenantMdc.capture();
            return delegate.submit(() -> {
                java.util.Map<String, String> workerBefore = TenantMdc.capture();
                TenantMdc.restore(mdc);
                try {
                    if (tenant != null) {
                        return TenantContext.callAs(tenant, task);
                    }
                    return task.call();
                }
                finally {
                    TenantMdc.restore(workerBefore);
                }
            });
        }
    }
}
