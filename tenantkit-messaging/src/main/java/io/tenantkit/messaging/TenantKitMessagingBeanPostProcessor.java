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

import java.util.Collection;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;

import io.tenantkit.messaging.properties.TenantKitMessagingProperties;

/**
 * Attaches the publisher post-processor and the listener advice during
 * <strong>{@code postProcessBeforeInitialization}</strong>, the same phase
 * discipline as the starter's executor wrapper: containers build their
 * consumers at initialization, so advice attached afterwards would race the
 * first deliveries.
 *
 * <p><strong>Idempotence.</strong> Both attach points are checked for a
 * pre-existing kit artifact first: {@code RabbitTemplate#getBeforePublishPostProcessors()}
 * is scanned for any {@link TenantHeaderMessagePostProcessor} instance, and
 * the factory advice chain for any {@link TenantContextAdvice}. A doubled
 * auto-configuration (or a user pre-wiring the kit parts by hand) can never
 * double-stamp.
 *
 * <p><strong>Advice merge order (DESIGN &sect;6.3).</strong> The kit advice
 * is always inserted <em>first</em> in the chain, so the tenant context is
 * bound before any user advice runs and cleared after the last one finishes.
 * When the user assembles their own advice chain on the factory, TenantKit
 * does <em>not</em> promise a global order for arbitrary combinations &mdash;
 * it only guarantees its own insertion position and documents a verification
 * recipe (see the reference guide).
 *
 * <p>API notes verified against Spring AMQP 4.0.x: {@code ContainerCustomizer}
 * stayed in {@code org.springframework.amqp.rabbit.config}; the factory's
 * {@code setAdviceChain(Advice...)} is overwrite (not append) semantics, which
 * is exactly why existing entries are read back via {@code getAdviceChain()}
 * and merged here.
 */
public class TenantKitMessagingBeanPostProcessor implements BeanPostProcessor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TenantKitMessagingBeanPostProcessor.class);

    private final TenantHeaderMessagePostProcessor publisherProcessor;
    private final TenantContextAdvice listenerAdvice;
    private final java.util.Set<String> rawContainerFactories;

    public TenantKitMessagingBeanPostProcessor(TenantKitMessagingProperties properties) {
        this.publisherProcessor = new TenantHeaderMessagePostProcessor(properties.getPublisherMissingContext());
        this.listenerAdvice = new TenantContextAdvice(properties.getListenerMissingHeader());
        this.rawContainerFactories = properties.getRawContainerFactories() == null
                ? java.util.Set.of()
                : java.util.Set.copyOf(properties.getRawContainerFactories());
    }

    /** Publisher-side artifact this processor stamps with (exposed for tests/diagnostics). */
    public TenantHeaderMessagePostProcessor publisherProcessor() {
        return this.publisherProcessor;
    }

    /** Listener-side artifact this processor attaches (exposed for tests/diagnostics). */
    public TenantContextAdvice listenerAdvice() {
        return this.listenerAdvice;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof RabbitTemplate template) {
            attachPublisherProcessor(template);
        }
        else if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) {
            // base type: covers Simple AND Direct factories — a Direct factory without
            // the advice silently drops both tenant binding and the missing-header policy.
            // Excluded factories (DLQ triage) stay raw by configuration: re-attaching the
            // advice there would reject dead letters a second time and lose them.
            if (!rawContainerFactories.contains(beanName)) {
                attachListenerAdvice(factory);
            }
        }
        return bean;
    }

    private void attachPublisherProcessor(RabbitTemplate template) {
        Collection<MessagePostProcessor> existing = template.getBeforePublishPostProcessors();
        if (existing != null) {
            for (MessagePostProcessor candidate : existing) {
                if (candidate instanceof TenantHeaderMessagePostProcessor preWired) {
                    // the pre-wired instance wins (the user wired it deliberately), but a
                    // policy divergence must be visible: the configured value would otherwise
                    // silently never apply (R4 backlog #9)
                    if (preWired.missingContextPolicy() != this.publisherProcessor.missingContextPolicy()) {
                        LOG.warn("tenantkit.messaging: a pre-wired TenantHeaderMessagePostProcessor uses "
                                        + "publisher-missing-context={} but tenantkit.messaging.publisher-missing-context={} "
                                        + "is configured; the pre-wired instance stays in charge",
                                preWired.missingContextPolicy(), this.publisherProcessor.missingContextPolicy());
                    }
                    return;
                }
            }
        }
        // set* is REPLACE semantics on RabbitTemplate: merge any user-registered
        // post-processors ahead of ours instead of silently dropping them
        MessagePostProcessor[] merged;
        int existingCount = existing == null ? 0 : existing.size();
        merged = new MessagePostProcessor[1 + existingCount];
        if (existingCount > 0) {
            int i = 0;
            for (MessagePostProcessor mpp : existing) {
                merged[i++] = mpp;
            }
        }
        merged[existingCount] = this.publisherProcessor;
        template.setBeforePublishPostProcessors(merged);
    }

    private void attachListenerAdvice(AbstractRabbitListenerContainerFactory<?> factory) {
        Advice[] existing = factory.getAdviceChain();
        if (existing != null) {
            for (Advice advice : existing) {
                if (advice instanceof TenantContextAdvice preWired) {
                    if (preWired.missingHeaderPolicy() != this.listenerAdvice.missingHeaderPolicy()) {
                        LOG.warn("tenantkit.messaging: a pre-wired TenantContextAdvice uses "
                                        + "listener-missing-header={} but tenantkit.messaging.listener-missing-header={} "
                                        + "is configured; the pre-wired instance stays in charge",
                                preWired.missingHeaderPolicy(), this.listenerAdvice.missingHeaderPolicy());
                    }
                    return;
                }
            }
        }
        int existingCount = existing == null ? 0 : existing.length;
        Advice[] merged = new Advice[1 + existingCount];
        merged[0] = this.listenerAdvice;
        if (existingCount > 0) {
            System.arraycopy(existing, 0, merged, 1, existingCount);
        }
        factory.setAdviceChain(merged);
    }
}
