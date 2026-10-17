package io.tenantkit.messaging;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.async.TenantMdc;
import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantMessagingException;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.ListenerMissingHeader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Listener-side unit behaviour with a fake {@code MethodInvocation} (no
 * broker): context+MDC restore, both missing-header policies, invalid header
 * charset, and the double cleanup after delivery.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantContextAdviceTest {

    private static final TenantId ALPHA = TenantId.of("alpha");
    private static final TenantId BETA = TenantId.of("beta");

    private static Message messageWithHeader(String value) {
        MessageProperties properties = new MessageProperties();
        if (value != null) {
            properties.setHeader(TenantMessagingHeaders.TENANT_HEADER, value);
        }
        return new Message("p".getBytes(), properties);
    }

    @Test
    void restoresContextAndMdcAroundProceedAndClearsBothAfterwards() {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        AtomicReference<TenantId> seenInside = new AtomicReference<>();
        AtomicReference<String> mdcInside = new AtomicReference<>();
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader("beta"),
                () -> {
                    seenInside.set(TenantContext.currentOrNull());
                    mdcInside.set(MDC.get(TenantMdc.TENANT_KEY));
                });

        TenantContext.runAs(ALPHA, () -> {
            try {
                advice.invoke(invocation);
                // after the delivery: outer binding restored, MDC clean
                assertThat(TenantContext.currentOrNull()).isSameAs(ALPHA);
                assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
            }
            catch (Throwable ex) {
                throw new AssertionError(ex);
            }
        });

        assertThat(seenInside.get()).isEqualTo(BETA);
        assertThat(mdcInside.get()).isEqualTo("beta");
        assertThat(invocation.proceeded()).isTrue();
    }

    @Test
    void worksWithoutOuterBindingAndLeavesThreadClean() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        AtomicReference<TenantId> seenInside = new AtomicReference<>();
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader("alpha"),
                () -> seenInside.set(TenantContext.currentOrNull()));

        advice.invoke(invocation);

        assertThat(seenInside.get()).isEqualTo(ALPHA);
        assertThat(TenantContext.currentOrNull()).isNull();
        assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenListenerFails() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader("alpha"),
                null, new IllegalStateException("handler blew up"));

        assertThatThrownBy(() -> advice.invoke(invocation)).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void missingHeaderRejectsToDlqByDefault() {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader(null));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("missing tenant header");
        assertThat(invocation.proceeded()).isFalse();
        assertThat(advice.missingHeaderPolicy()).isEqualTo(ListenerMissingHeader.REJECT_TO_DLQ);
    }

    @Test
    void missingHeaderCarriesTk3002() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation(new Message("p".getBytes(),
                new MessageProperties()));

        assertThatThrownBy(() -> advice.invoke(invocation)).satisfies(ex -> {
            assertThat(ex).isInstanceOf(AmqpRejectAndDontRequeueException.class);
            Throwable cause = ex.getCause();
            assertThat(cause).isInstanceOf(TenantMessagingException.class);
            assertThat(((TenantMessagingException) cause).errorCode())
                    .isEqualTo(TenantMessagingErrorCodes.LISTENER_MISSING_HEADER);
        });
    }

    @Test
    void blankHeaderCountsAsMissing() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader("   "));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("missing tenant header");
    }

    @Test
    void logAndAckProcessesWithoutContextAndWarns(CapturedOutput output) throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.LOG_AND_ACK);
        AtomicReference<TenantId> seenInside = new AtomicReference<>();
        FakeListenerInvocation invocation = new FakeListenerInvocation(new Message("p".getBytes(),
                new MessageProperties()), () -> seenInside.set(TenantContext.currentOrNull()));

        advice.invoke(invocation);

        assertThat(invocation.proceeded()).isTrue();
        assertThat(seenInside.get()).isNull();
        assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
        assertThat(output.getAll()).contains("log-and-ack");
        assertThat(advice.missingHeaderPolicy()).isEqualTo(ListenerMissingHeader.LOG_AND_ACK);
    }

    @Test
    void invalidHeaderValueRejectsWithTk3003() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation(messageWithHeader("BAD_TENANT"));

        assertThatThrownBy(() -> advice.invoke(invocation)).satisfies(ex -> {
            assertThat(ex).isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasMessageContaining("charset");
            assertThat(ex.getCause()).isInstanceOf(TenantMessagingException.class);
            assertThat(((TenantMessagingException) ex.getCause()).errorCode())
                    .isEqualTo(TenantMessagingErrorCodes.LISTENER_INVALID_HEADER);
            // R2: format exception rides as the cause (no addSuppressed), raw value never echoed
            assertThat(ex.getCause().getCause()).isInstanceOf(TenantIdFormatException.class);
            assertThat(ex.getMessage()).doesNotContain("BAD_TENANT");
        });
        assertThat(invocation.proceeded()).isFalse();
    }

    @Test
    void byteHeaderValuesAreDecoded() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        MessageProperties properties = new MessageProperties();
        properties.setHeader(TenantMessagingHeaders.TENANT_HEADER, "alpha".getBytes());
        AtomicReference<TenantId> seenInside = new AtomicReference<>();
        FakeListenerInvocation invocation = new FakeListenerInvocation(
                new Message("p".getBytes(), properties), () -> seenInside.set(TenantContext.currentOrNull()));

        advice.invoke(invocation);

        assertThat(seenInside.get()).isEqualTo(ALPHA);
    }

    @Test
    void nonStringHeaderObjectsFallBackToToString() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        MessageProperties properties = new MessageProperties();
        // mirrors broker long-string representations after a dead-letter round trip
        properties.setHeader(TenantMessagingHeaders.TENANT_HEADER,
                new Object() {
                    @Override
                    public String toString() {
                        return "alpha";
                    }
                });
        AtomicReference<TenantId> seenInside = new AtomicReference<>();
        FakeListenerInvocation invocation = new FakeListenerInvocation(
                new Message("p".getBytes(), properties), () -> seenInside.set(TenantContext.currentOrNull()));

        advice.invoke(invocation);

        assertThat(seenInside.get()).isEqualTo(ALPHA);
    }

    @Test
    void invocationWithoutMessageArgumentStaysTransparent() throws Throwable {
        TenantContextAdvice advice = new TenantContextAdvice(ListenerMissingHeader.REJECT_TO_DLQ);
        FakeListenerInvocation invocation = new FakeListenerInvocation("plain", "args");

        advice.invoke(invocation);

        assertThat(invocation.proceeded()).isTrue();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void mdcSnapshotMethodBehaves() {
        Map<String, String> empty = TenantMdc.capture();
        assertThat(empty).isEmpty();
        MDC.put(TenantMdc.TENANT_KEY, "alpha");
        assertThat(TenantMdc.capture()).containsEntry(TenantMdc.TENANT_KEY, "alpha");
        TenantMdc.restore(null);
        assertThat(MDC.get(TenantMdc.TENANT_KEY)).isNull();
    }
}
