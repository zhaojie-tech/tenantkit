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
package io.tenantkit.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.tenantkit.messaging.properties.TenantKitMessagingProperties;

/**
 * RabbitMQ propagation assembly (DESIGN &sect;5/§6.3). The module backs off
 * entirely without Spring AMQP on the classpath, so adding the jar to a
 * non-AMQP application changes nothing.
 *
 * <p>Boot 4 note (ADR-0003): consumers need {@code spring-boot-starter-amqp};
 * bare {@code spring-rabbit} carries no auto-configuration.
 */
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(name = "tenantkit.messaging.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantKitMessagingProperties.class)
public class TenantKitMessagingAutoConfiguration {

    /**
     * Wires publisher and listener propagation onto every
     * {@link RabbitTemplate} and {@code AbstractRabbitListenerContainerFactory (Simple and Direct)}
     * the context manages, in the before-initialization phase, idempotently
     * (see {@link TenantKitMessagingBeanPostProcessor} for the attach-point
     * contract). Static so the post-processor is registered before regular
     * beans are created.
     */
    @Bean
    @ConditionalOnMissingBean(TenantKitMessagingBeanPostProcessor.class)
    static TenantKitMessagingBeanPostProcessor tenantKitMessagingBeanPostProcessor(
            TenantKitMessagingProperties properties) {
        return new TenantKitMessagingBeanPostProcessor(properties);
    }
}
