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
package io.tenantkit.demo.async;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.tenantkit.core.TenantContext;

/**
 * Runs on {@code applicationTaskExecutor}, which the starter decorates with
 * {@code TenantPropagationTaskDecorator} &mdash; the tenant travels to the
 * worker thread without any demo-side code.
 */
@Component
public class DemoAsyncWorker {

    @Async
    public CompletableFuture<String> echoFromAsyncPool() {
        return CompletableFuture.completedFuture(
                TenantContext.currentOrNull() == null ? "none" : TenantContext.required().value());
    }
}
