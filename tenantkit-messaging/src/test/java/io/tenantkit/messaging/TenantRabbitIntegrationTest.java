package io.tenantkit.messaging;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.RabbitMQContainer;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.async.TenantMdc;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.ListenerMissingHeader;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.PublisherMissingContext;
import io.tenantkit.test.TenantSupport;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Slow layer (requires Docker; {@code mvn test -Pcontainers}): real DLX
 * round trips through {@link TenantSupport#rabbitmq()}.
 *
 * <ul>
 * <li><strong>Positive</strong> (DESIGN &sect;6.3): a headered message is
 * rejected by the work listener, dead-letters to the DLQ, and the DLQ
 * consumer restores the tenant context from the preserved header &mdash;
 * {@code x-death} appended, {@code x-tenant-id} retained.</li>
 * <li><strong>Negative</strong> (the real cut-line case of 0.1.1, DESIGN
 * &sect;11): a headerless message under {@code reject-to-dlq} lands in the
 * DLQ exactly once instead of looping &mdash; the handler never executes and
 * the work queue drains. Registered as the 0.1.1 issue item per the cut-line
 * declaration (in-repo here as a slow test rather than an empty promise).</li>
 * </ul>
 */
@Tag("slow")
class TenantRabbitIntegrationTest {

    private static final String POSITIVE_WORK = "tk.it.pos.work";
    private static final String POSITIVE_DLQ = "tk.it.pos.dlq";
    private static final String NEGATIVE_WORK = "tk.it.neg.work";
    private static final String NEGATIVE_DLQ = "tk.it.neg.dlq";

    private static RabbitMQContainer rabbit;
    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void startBrokerAndApplication() {
        rabbit = TenantSupport.rabbitmq();
        context = new AnnotationConfigApplicationContext(RabbitItConfig.class);
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    private static RabbitTemplate template() {
        return context.getBean(RabbitTemplate.class);
    }

    private static Recorder recorder() {
        return context.getBean(Recorder.class);
    }

    @Test
    void headeredMessageSurvivesDlqRoundTripAndRestoresContext() throws Exception {
        Recorder recorder = recorder();
        TenantContext.runAs(TenantId.of("alpha"),
                () -> template().convertAndSend("", POSITIVE_WORK, "round-trip-me"));

        assertThat(recorder.positiveDlq.await(20, TimeUnit.SECONDS)).isTrue();

        Message deadLettered = recorder.positiveMessage.get();
        Object header = deadLettered.getMessageProperties().getHeader(TenantMessagingHeaders.TENANT_HEADER);
        assertThat(header).isEqualTo("alpha");
        assertThat(deadLettered.getMessageProperties().getHeaders()).containsKey("x-death");
        assertThat(recorder.positiveTenant.get()).isEqualTo(TenantId.of("alpha"));
        assertThat(recorder.positiveMdc.get()).isEqualTo("alpha");
    }

    @Test
    void headerlessMessageGoesToDlqExactlyOnceWithoutLooping() {
        Recorder recorder = recorder();
        template().convertAndSend("", NEGATIVE_WORK, "poison-me");

        // exactly once: the ALWAYS-ON DLQ consumer records the delivery — never a
        // receive() probe, which competes with the @RabbitListener for the message
        // (R1 test-audit P0-3: the probe lost the race and the assertion hung)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(recorder.negativeDlqCount.get()).as("exactly one DLQ delivery").isEqualTo(1));
        Message deadLettered = recorder.negativeMessage.get();
        Object header = deadLettered.getMessageProperties().getHeader(TenantMessagingHeaders.TENANT_HEADER);
        assertThat(header).as("headerless in, headerless dead-lettered").isNull();
        assertThat(deadLettered.getMessageProperties().getHeaders()).containsKey("x-death");

        // loop prevention: handler never ran, work queue drains and stays empty, DLQ depth stable
        assertThat(recorder.negativeWorkCalls.get()).as("reject-to-dlq must run before the handler").isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(template().receive(NEGATIVE_WORK, 500)).isNull();
        });
        long dlqAfterDrain = dlqDepth(NEGATIVE_DLQ);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> dlqDepth(NEGATIVE_DLQ) == dlqAfterDrain);
        assertThat(recorder.negativeDlqCount.get()).as("no re-loop after the drain window").isEqualTo(1);
    }

    private static long dlqDepth(String queue) {
        return Optional.ofNullable(template().execute(channel -> channel.messageCount(queue)))
                .orElse(0L);
    }

    static class Recorder {

        final CountDownLatch positiveDlq = new CountDownLatch(1);
        final AtomicReference<Message> positiveMessage = new AtomicReference<>();
        final AtomicReference<TenantId> positiveTenant = new AtomicReference<>();
        final AtomicReference<String> positiveMdc = new AtomicReference<>();
        final AtomicInteger negativeWorkCalls = new AtomicInteger();
        final AtomicInteger negativeDlqCount = new AtomicInteger();
        final AtomicReference<Message> negativeMessage = new AtomicReference<>();
    }

    @Configuration
    @EnableRabbit
    static class RabbitItConfig {

        @Bean
        CachingConnectionFactory connectionFactory() {
            return new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        }

        @Bean
        RabbitAdmin rabbitAdmin(CachingConnectionFactory connectionFactory) {
            return new RabbitAdmin(connectionFactory);
        }

        @Bean
        org.springframework.amqp.core.Queue positiveWorkQueue() {
            return QueueBuilder.durable(POSITIVE_WORK)
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(POSITIVE_DLQ)
                    .build();
        }

        @Bean
        org.springframework.amqp.core.Queue positiveDlqQueue() {
            return QueueBuilder.durable(POSITIVE_DLQ).build();
        }

        @Bean
        org.springframework.amqp.core.Queue negativeWorkQueue() {
            return QueueBuilder.durable(NEGATIVE_WORK)
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(NEGATIVE_DLQ)
                    .build();
        }

        @Bean
        org.springframework.amqp.core.Queue negativeDlqQueue() {
            return QueueBuilder.durable(NEGATIVE_DLQ).build();
        }

        @Bean
        RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory) {
            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            template.setBeforePublishPostProcessors(
                    new TenantHeaderMessagePostProcessor(PublisherMissingContext.SKIP_WITH_WARN));
            return template;
        }

        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAdviceChain(new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ));
            return factory;
        }

        /**
         * DLQ-triage factory: deliberately carries NO tenant advice. The negative
         * message has no header by definition; re-running the advice here would
         * reject it a second time at the DLQ entrance (no further dead-letter
         * target) and lose it instead of letting a human see it.
         */
        @Bean
        SimpleRabbitListenerContainerFactory tenantkitRawDlqFactory(CachingConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            return factory;
        }

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        ItListeners itListeners(Recorder recorder) {
            return new ItListeners(recorder);
        }
    }

    static class ItListeners {

        private final Recorder recorder;

        ItListeners(Recorder recorder) {
            this.recorder = recorder;
        }

        /** Positive leg: deliberately rejects so the message takes the DLX round trip. */
        @RabbitListener(queues = POSITIVE_WORK)
        void positiveWork(Message message) {
            throw new AmqpRejectAndDontRequeueException("planned rejection: send the message through the DLX");
        }

        @RabbitListener(queues = POSITIVE_DLQ)
        void positiveDlq(Message message) {
            this.recorder.positiveMessage.set(message);
            this.recorder.positiveTenant.set(TenantContext.currentOrNull());
            this.recorder.positiveMdc.set(MDC.get(TenantMdc.TENANT_KEY));
            this.recorder.positiveDlq.countDown();
        }

        @RabbitListener(queues = NEGATIVE_WORK)
        void negativeWork(Message message) {
            this.recorder.negativeWorkCalls.incrementAndGet();
        }

        @RabbitListener(queues = NEGATIVE_DLQ, containerFactory = "tenantkitRawDlqFactory")
        void negativeDlq(Message message) {
            this.recorder.negativeMessage.set(message);
            this.recorder.negativeDlqCount.incrementAndGet();
        }
    }
}
