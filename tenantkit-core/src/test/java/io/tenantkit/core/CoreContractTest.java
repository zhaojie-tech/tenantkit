package io.tenantkit.core;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantKitException;
import io.tenantkit.core.exception.TenantMessagingException;
import io.tenantkit.core.exception.TenantMissingException;
import io.tenantkit.core.exception.TenantProvisioningException;
import io.tenantkit.core.exception.UnknownTenantException;

import static org.assertj.core.api.Assertions.assertThat;

/** Exception hierarchy & request snapshot contracts (DESIGN §6.1/§7). */
class CoreContractTest {

    @Test
    void exceptionCarriesStableCodeMessageAndCause() {
        Throwable cause = new IllegalStateException("root");
        TenantProvisioningException withCause =
                new TenantProvisioningException("TK-2001", "checksum mismatch", cause);
        assertThat(withCause.errorCode()).isEqualTo("TK-2001");
        assertThat(withCause.getMessage()).isEqualTo("checksum mismatch");
        assertThat(withCause.getCause()).isSameAs(cause);
        assertThat(withCause).isInstanceOf(TenantKitException.class);
    }

    @Test
    void messagingExceptionCauseConstructorKeepsCode() {
        Throwable cause = new IllegalStateException("root");
        TenantMessagingException withCause = new TenantMessagingException("TK-3002", "msg", cause);
        assertThat(withCause.errorCode()).isEqualTo("TK-3002");
        assertThat(withCause.getCause()).isSameAs(cause);
    }

    @Test
    void reservedCodeFamiliesExistFromDayOne() {
        assertThat(new TenantMissingException("m").errorCode()).isEqualTo("TK-1001");
        assertThat(new UnknownTenantException("m").errorCode()).isEqualTo("TK-1002");
        assertThat(new TenantIdFormatException("m").errorCode()).isEqualTo("TK-1003");
        assertThat(new TenantProvisioningException("TK-2999", "m").errorCode()).isEqualTo("TK-2999");
        assertThat(new TenantMessagingException("TK-3001", "m").errorCode()).isEqualTo("TK-3001");
    }

    @Test
    void requestSnapshotDefaultsNullHeadersToEmpty() {
        TenantResolutionRequest request = new TenantResolutionRequest(
                URI.create("https://acme.example.com/api"), "acme.example.com", null, null);
        MultiValueMap<String, String> headers = request.headers();
        assertThat(headers).isNotNull().isEmpty();
    }

    @Test
    void requestSnapshotKeepsProvidedHeaders() {
        LinkedMultiValueMap<String, String> provided = new LinkedMultiValueMap<>();
        provided.add("X-Tenant-Id", "alpha");
        TenantResolutionRequest request = new TenantResolutionRequest(
                URI.create("https://acme.example.com/api"), "acme.example.com", provided, null);
        assertThat(request.headers().getFirst("X-Tenant-Id")).isEqualTo("alpha");
    }

    @Test
    void activePatternReflectsConfiguration() {
        assertThat(TenantId.activePattern()).isEqualTo("[a-z0-9-]{1,32}");
        TenantId.usePattern("[a-z]{1,4}");
        assertThat(TenantId.activePattern()).isEqualTo("[a-z]{1,4}");
        TenantId.resetPattern();
    }
}
