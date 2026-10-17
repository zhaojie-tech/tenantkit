package io.tenantkit.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMessagingException;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.PublisherMissingContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publisher-side unit behaviour (no broker): header written from the bound
 * tenant; both missing-context policies. Tests never leave a bound context
 * behind: every {@code runAs} scope restores its outer (empty) binding on
 * exit.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantHeaderMessagePostProcessorTest {

    private static final TenantId ALPHA = TenantId.of("alpha");

    @Test
    void writesHeaderFromBoundTenant() {
        Message message = new Message("p".getBytes(), new MessageProperties());
        TenantHeaderMessagePostProcessor processor =
                new TenantHeaderMessagePostProcessor(PublisherMissingContext.SKIP_WITH_WARN);
        Message[] stamped = new Message[1];

        TenantContext.runAs(ALPHA, () -> stamped[0] = processor.postProcessMessage(message));

        assertThat(stamped[0]).isSameAs(message);
        Object header = message.getMessageProperties().getHeader(TenantMessagingHeaders.TENANT_HEADER);
        assertThat(header).isEqualTo("alpha");
        assertThat(processor.missingContextPolicy()).isEqualTo(PublisherMissingContext.SKIP_WITH_WARN);
    }

    @Test
    void skipWithWarnSendsUnchangedWhenNoTenant(CapturedOutput output) {
        Message message = new Message("p".getBytes(), new MessageProperties());
        TenantHeaderMessagePostProcessor processor =
                new TenantHeaderMessagePostProcessor(PublisherMissingContext.SKIP_WITH_WARN);

        Message result = processor.postProcessMessage(message);

        assertThat(result).isSameAs(message);
        Object header = message.getMessageProperties().getHeader(TenantMessagingHeaders.TENANT_HEADER);
        assertThat(header).isNull();
        assertThat(output.getAll()).contains("no tenant");
    }

    @Test
    void failPolicyAbendsWithTk3001() {
        Message message = new Message("p".getBytes(), new MessageProperties());
        TenantHeaderMessagePostProcessor processor =
                new TenantHeaderMessagePostProcessor(PublisherMissingContext.FAIL);

        assertThatThrownBy(() -> processor.postProcessMessage(message))
                .isInstanceOfSatisfying(TenantMessagingException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(TenantMessagingErrorCodes.PUBLISHER_NO_TENANT);
                    assertThat(ex).hasMessageContaining("no tenant");
                });
    }

    @Test
    void headerConstantIsTheLowerCaseWireContract() {
        assertThat(TenantMessagingHeaders.TENANT_HEADER).isEqualTo("x-tenant-id");
    }
}
