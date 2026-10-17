package io.tenantkit.messaging;

import io.tenantkit.messaging.properties.TenantKitMessagingProperties;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.ListenerMissingHeader;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.PublisherMissingContext;
import io.tenantkit.test.HidingClassLoader;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Messaging assembly matrix, cases MQ01&ndash;MQ10 (appended domain to the
 * frozen table in docs/matrix/assembly-matrix.md, which covers W + K; the
 * table's discipline allows appending new domains without renumbering).
 * Behaviour-layer cases live in the unit tests next to this one.
 */
class TenantKitMessagingAssemblyMatrixTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantKitMessagingAutoConfiguration.class))
            .withUserConfiguration(AmqpUserConfig.class);

    /** MQ01 — no Spring AMQP on the classpath: whole module backs off silently. */
    @Test
    void mq01_missingAmqpBacksOff() {
        ApplicationContextRunner hiding = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantKitMessagingAutoConfiguration.class))
                .withClassLoader(new HidingClassLoader(getClass().getClassLoader(),
                        "org.springframework.amqp."));
        hiding.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantKitMessagingBeanPostProcessor.class);
            assertThat(context.getBeansOfType(TenantKitMessagingProperties.class)).isEmpty();
        });
    }

    /** MQ02 — module switch off: no kit beans, user template/factory untouched. */
    @Test
    void mq02_moduleSwitchDisablesEverything() {
        runner.withPropertyValues("tenantkit.messaging.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantKitMessagingBeanPostProcessor.class);
            RabbitTemplate template = context.getBean(RabbitTemplate.class);
            assertThat(template.getBeforePublishPostProcessors()).isNull();
            assertThat(context).as("user beans must survive the module switch")
                    .hasBean("userRabbitTemplate");
        });
    }

    /** MQ03 — defaults: post-processor registered, both artifacts attached to user beans. */
    @Test
    void mq03_defaultAssemblyAttachesBothArtifacts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TenantKitMessagingBeanPostProcessor.class);
            RabbitTemplate template = context.getBean("userRabbitTemplate", RabbitTemplate.class);
            assertThat(template.getBeforePublishPostProcessors())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(TenantHeaderMessagePostProcessor.class);
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("userListenerFactory", SimpleRabbitListenerContainerFactory.class);
            Advice[] chain = factory.getAdviceChain();
            assertThat(chain).hasSize(1);
            assertThat(chain[0]).isInstanceOf(TenantContextAdvice.class);
        });
    }

    /** MQ04 — default policies bind: skip-with-warn / reject-to-dlq. */
    @Test
    void mq04_defaultPoliciesBound() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TenantKitMessagingProperties properties = context.getBean(TenantKitMessagingProperties.class);
            assertThat(properties.getPublisherMissingContext()).isEqualTo(PublisherMissingContext.SKIP_WITH_WARN);
            assertThat(properties.getListenerMissingHeader()).isEqualTo(ListenerMissingHeader.REJECT_TO_DLQ);
            TenantKitMessagingBeanPostProcessor processor =
                    context.getBean(TenantKitMessagingBeanPostProcessor.class);
            assertThat(processor.publisherProcessor().missingContextPolicy())
                    .isEqualTo(PublisherMissingContext.SKIP_WITH_WARN);
            assertThat(processor.listenerAdvice().missingHeaderPolicy())
                    .isEqualTo(ListenerMissingHeader.REJECT_TO_DLQ);
        });
    }

    /** MQ05 — publisher-missing-context=fail binds into the attached processor. */
    @Test
    void mq05_failPublisherPolicyBinds() {
        runner.withPropertyValues("tenantkit.messaging.publisher-missing-context=fail").run(context -> {
            assertThat(context).hasNotFailed();
            TenantKitMessagingBeanPostProcessor processor =
                    context.getBean(TenantKitMessagingBeanPostProcessor.class);
            assertThat(processor.publisherProcessor().missingContextPolicy())
                    .isEqualTo(PublisherMissingContext.FAIL);
        });
    }

    /** MQ06 — listener-missing-header=log-and-ack binds into the attached advice. */
    @Test
    void mq06_logAndAckListenerPolicyBinds() {
        runner.withPropertyValues("tenantkit.messaging.listener-missing-header=log-and-ack").run(context -> {
            assertThat(context).hasNotFailed();
            TenantKitMessagingBeanPostProcessor processor =
                    context.getBean(TenantKitMessagingBeanPostProcessor.class);
            assertThat(processor.listenerAdvice().missingHeaderPolicy())
                    .isEqualTo(ListenerMissingHeader.LOG_AND_ACK);
        });
    }

    /** MQ07 — unknown enum value fails binding (fail-fast, never silently dropped). */
    @Test
    void mq07_unknownPolicyValueFailsBinding() {
        runner.withPropertyValues("tenantkit.messaging.publisher-missing-context=whatever").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                    "tenantkit.messaging.publisher-missing-context");
        });
    }

    /**
     * MQ08 — repeat processing is idempotent: a user-supplied
     * {@link TenantKitMessagingBeanPostProcessor} of its own plus the
     * auto-configured one still leave exactly one kit artifact per bean.
     */
    @Test
    void mq08_repeatedPostProcessingStaysIdempotent() {
        runner.withUserConfiguration(DoubledProcessorConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            RabbitTemplate template = context.getBean("userRabbitTemplate", RabbitTemplate.class);
            assertThat(template.getBeforePublishPostProcessors())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(TenantHeaderMessagePostProcessor.class);
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("userListenerFactory", SimpleRabbitListenerContainerFactory.class);
            assertThat(factory.getAdviceChain()).hasSize(1);
        });
    }

    /** MQ09 — user advice chain: kit advice inserted first, user advice preserved (DESIGN §6.3). */
    @Test
    void mq09_kitAdviceLeadsUserChain() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantKitMessagingAutoConfiguration.class))
                .withUserConfiguration(UserAdviceConfig.class)
                .run(context -> {
            assertThat(context).hasNotFailed();
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("userListenerFactory", SimpleRabbitListenerContainerFactory.class);
            Advice[] chain = factory.getAdviceChain();
            assertThat(chain).hasSize(2);
            assertThat(chain[0]).isInstanceOf(TenantContextAdvice.class);
            assertThat(chain[1]).isInstanceOf(RecordingAdvice.class);
        });
    }

    /** MQ10 — user-owned processor/advice beans: auto-configuration does not stamp over them. */
    @Test
    void mq10_userPreWiredKitPartsRespected() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantKitMessagingAutoConfiguration.class))
                .withUserConfiguration(PreWiredConfig.class)
                .run(context -> {
            assertThat(context).hasNotFailed();
            RabbitTemplate template = context.getBean("preWiredTemplate", RabbitTemplate.class);
            assertThat(template.getBeforePublishPostProcessors())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(TenantHeaderMessagePostProcessor.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AmqpUserConfig {

        @Bean
        org.springframework.amqp.rabbit.connection.CachingConnectionFactory matrixConnectionFactory() {
            // construction is lazy: no broker contact at init time
            return new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("localhost");
        }

        @Bean
        RabbitTemplate userRabbitTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory cf) {
            return new RabbitTemplate(cf);
        }

        @Bean
        SimpleRabbitListenerContainerFactory userListenerFactory(
                org.springframework.amqp.rabbit.connection.ConnectionFactory cf) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(cf);
            return factory;
        }
    }

    /** Simulates a doubled registration of the assembly post-processor. */
    @Configuration(proxyBeanMethods = false)
    static class DoubledProcessorConfig {

        @Bean
        TenantKitMessagingBeanPostProcessor secondMessagingProcessor(TenantKitMessagingProperties properties) {
            return new TenantKitMessagingBeanPostProcessor(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAdviceConfig {

        @Bean
        org.springframework.amqp.rabbit.connection.CachingConnectionFactory adviceConfigConnectionFactory() {
            return new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("localhost");
        }

        @Bean
        SimpleRabbitListenerContainerFactory userListenerFactory(
                org.springframework.amqp.rabbit.connection.ConnectionFactory cf) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(cf);
            factory.setAdviceChain(new RecordingAdvice());
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PreWiredConfig {

        @Bean
        org.springframework.amqp.rabbit.connection.CachingConnectionFactory preWiredConnectionFactory() {
            return new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("localhost");
        }

        @Bean
        RabbitTemplate preWiredTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory cf) {
            RabbitTemplate template = new RabbitTemplate(cf);
            template.setBeforePublishPostProcessors(
                    new TenantHeaderMessagePostProcessor(PublisherMissingContext.SKIP_WITH_WARN));
            return template;
        }
    }

    static class RecordingAdvice implements Advice {
    }
}
