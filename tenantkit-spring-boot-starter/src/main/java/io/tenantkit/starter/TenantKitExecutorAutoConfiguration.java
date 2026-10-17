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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.tenantkit.starter.async.TenantExecutorBeanPostProcessor;

/**
 * Async propagation assembly (DESIGN &sect;6.3): always decorates
 * {@code applicationTaskExecutor}; user executor pools only with
 * {@code tenantkit.executor.decorate-user-pools=true}.
 */
@AutoConfiguration
@ConditionalOnClass(ThreadPoolTaskExecutor.class)
@EnableConfigurationProperties(TenantKitExecutorProperties.class)
public class TenantKitExecutorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    static TenantExecutorBeanPostProcessor tenantExecutorBeanPostProcessor(TenantKitExecutorProperties properties,
            org.springframework.beans.factory.ObjectProvider<org.springframework.core.task.TaskDecorator> userDecorators) {
        return new TenantExecutorBeanPostProcessor(properties.isDecorateUserPools(), userDecorators);
    }
}
