package io.tenantkit.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantMissingException;
import io.tenantkit.core.exception.UnknownTenantException;
import io.tenantkit.web.properties.TenantKitResolutionProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime ProblemDetail exit for TenantKitException raised inside MVC code. */
class TenantKitExceptionAdviceTest {

    private final TenantKitExceptionAdvice advice =
            new TenantKitExceptionAdvice(new TenantKitResolutionProperties());

    @Test
    void missingTenantIs400() {
        ResponseEntity<ProblemDetail> response = advice.onTenantKitException(new TenantMissingException("gone"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getDetail()).isEqualTo("gone");
        assertThat(String.valueOf(response.getBody().getType())).endsWith(TenantKitErrorCodes.TENANT_MISSING);
    }

    @Test
    void unknownTenantUsesConfiguredStatus() {
        TenantKitResolutionProperties properties = new TenantKitResolutionProperties();
        properties.setUnknownTenantStatus(404);
        TenantKitExceptionAdvice configured = new TenantKitExceptionAdvice(properties);
        ResponseEntity<ProblemDetail> response = configured.onTenantKitException(new UnknownTenantException("x"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void provisioningAndMessagingFamiliesAre500() {
        ResponseEntity<ProblemDetail> provisioning = advice.onTenantKitException(
                new io.tenantkit.core.exception.TenantProvisioningException("TK-2001", "checksum"));
        assertThat(provisioning.getStatusCode().value()).isEqualTo(500);
        ResponseEntity<ProblemDetail> messaging = advice.onTenantKitException(
                new io.tenantkit.core.exception.TenantMessagingException("TK-3001", "no tenant"));
        assertThat(messaging.getStatusCode().value()).isEqualTo(500);
        ResponseEntity<ProblemDetail> format = advice.onTenantKitException(
                new TenantIdFormatException("bad"));
        assertThat(format.getStatusCode().value()).isEqualTo(400);
    }
}
