package io.tenantkit.messaging;

import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import io.tenantkit.messaging.properties.TenantKitMessagingProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attach-point behaviour of the post-processor: one kit artifact per bean,
 * kit advice first in a merged chain, user pre-wiring respected.
 */
class TenantKitMessagingBeanPostProcessorTest {

    @Test
    void stampsRabbitTemplateOnceEvenWhenProcessedTwice() {
        TenantKitMessagingProperties properties = new TenantKitMessagingProperties();
        TenantKitMessagingBeanPostProcessor processor = new TenantKitMessagingBeanPostProcessor(properties);
        RabbitTemplate template = new RabbitTemplate();

        processor.postProcessBeforeInitialization(template, "rabbitTemplate");
        processor.postProcessBeforeInitialization(template, "rabbitTemplate");

        assertThat(template.getBeforePublishPostProcessors())
                .hasSize(1)
                .first()
                .isInstanceOf(TenantHeaderMessagePostProcessor.class);
    }

    @Test
    void skipsTemplateWhenUserPreAttachedKitProcessor() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        RabbitTemplate template = new RabbitTemplate();
        TenantHeaderMessagePostProcessor userProcessor =
                new TenantHeaderMessagePostProcessor(properties().getPublisherMissingContext());
        template.setBeforePublishPostProcessors(userProcessor);

        processor.postProcessBeforeInitialization(template, "rabbitTemplate");

        assertThat(template.getBeforePublishPostProcessors()).containsExactly(userProcessor);
    }

    @Test
    void attachesAdviceAndInsertsKitAdviceFirstInMergedChain() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        Advice userAdvice = new MarkerAdvice();

        factory.setAdviceChain(userAdvice);
        processor.postProcessBeforeInitialization(factory, "rabbitListenerContainerFactory");

        Advice[] chain = factory.getAdviceChain();
        assertThat(chain).hasSize(2);
        assertThat(chain[0]).isInstanceOf(TenantContextAdvice.class);
        assertThat(chain[1]).isSameAs(userAdvice);
    }

    @Test
    void mergesWhenFactoryHasNoExistingAdvice() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        assertThat(factory.getAdviceChain()).isNull();

        processor.postProcessBeforeInitialization(factory, "rabbitListenerContainerFactory");

        assertThat(factory.getAdviceChain()).hasSize(1);
    }

    @Test
    void skipsFactoryWhenUserPreAttachedKitAdvice() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        TenantContextAdvice userAdvice = new TenantContextAdvice(properties().getListenerMissingHeader());
        factory.setAdviceChain(userAdvice);

        processor.postProcessBeforeInitialization(factory, "rabbitListenerContainerFactory");

        assertThat(factory.getAdviceChain()).containsExactly(userAdvice);
    }

    /** aopalliance Advice is a marker interface, not a functional interface. */
    static class MarkerAdvice implements Advice {
    }

    @Test
    void doubleProcessingKeepsSingleAdviceEntry() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        processor.postProcessBeforeInitialization(factory, "f");
        processor.postProcessBeforeInitialization(factory, "f");

        assertThat(factory.getAdviceChain()).hasSize(1);
    }

    @Test
    void policiesFlowFromPropertiesIntoArtifacts() {
        TenantKitMessagingProperties properties = new TenantKitMessagingProperties();
        TenantKitMessagingBeanPostProcessor processor = new TenantKitMessagingBeanPostProcessor(properties);

        assertThat(processor.publisherProcessor().missingContextPolicy())
                .isEqualTo(properties.getPublisherMissingContext());
        assertThat(processor.listenerAdvice().missingHeaderPolicy())
                .isEqualTo(properties.getListenerMissingHeader());
    }

    @Test
    void nonAmqpBeansPassThroughUntouched() {
        TenantKitMessagingBeanPostProcessor processor =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        Object plain = new Object();

        assertThat(processor.postProcessBeforeInitialization(plain, "plain")).isSameAs(plain);
    }

    private static TenantKitMessagingProperties properties() {
        return new TenantKitMessagingProperties();
    }

    /** R4 fix lock-in: a DirectRabbitListenerContainerFactory must receive the advice too. */
    @Test
    void directFactoryAlsoReceivesTheAdvice() {
        TenantKitMessagingBeanPostProcessor bpp =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory direct =
                new org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory();
        bpp.postProcessBeforeInitialization(direct, "directFactory");
        assertThat(direct.getAdviceChain()).hasSize(1)
                .allSatisfy(advice -> assertThat(advice).isInstanceOf(TenantContextAdvice.class));

        // idempotent on re-processing (base-type branch, artifact scan)
        bpp.postProcessBeforeInitialization(direct, "directFactory");
        assertThat(direct.getAdviceChain()).hasSize(1);
    }

    /** R4 fix lock-in: user post-processors are merged ahead of the kit processor, not replaced. */
    @Test
    void userPublisherPostProcessorsSurviveMerging() {
        TenantKitMessagingBeanPostProcessor bpp =
                new TenantKitMessagingBeanPostProcessor(new TenantKitMessagingProperties());
        org.springframework.amqp.rabbit.core.RabbitTemplate template =
                new org.springframework.amqp.rabbit.core.RabbitTemplate();
        org.springframework.amqp.core.MessagePostProcessor userMpp = message -> message;
        template.setBeforePublishPostProcessors(userMpp);

        bpp.postProcessBeforeInitialization(template, "rabbitTemplate");
        java.util.Collection<org.springframework.amqp.core.MessagePostProcessor> merged =
                template.getBeforePublishPostProcessors();
        assertThat(merged).hasSize(2)
                .anyMatch(TenantHeaderMessagePostProcessor.class::isInstance)
                .contains(userMpp);
    }
}
