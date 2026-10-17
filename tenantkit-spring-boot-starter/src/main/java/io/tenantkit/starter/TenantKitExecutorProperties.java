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
package io.tenantkit.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Executor decoration switches ({@code tenantkit.executor.*}, DESIGN &sect;6.3). */
@ConfigurationProperties(prefix = "tenantkit.executor")
public class TenantKitExecutorProperties {

    /**
     * Wrap every Spring-managed {@code ThreadPoolTaskExecutor}/{@code
     * SimpleAsyncTaskExecutor} bean, not only {@code applicationTaskExecutor}.
     * Default {@code false}; raw ExecutorService beans and
     * ForkJoinPool.commonPool are never covered either way &mdash; use
     * TenantExecutors.wrap for those.
     */
    private boolean decorateUserPools = false;

    public boolean isDecorateUserPools() {
        return decorateUserPools;
    }

    public void setDecorateUserPools(boolean decorateUserPools) {
        this.decorateUserPools = decorateUserPools;
    }
}
