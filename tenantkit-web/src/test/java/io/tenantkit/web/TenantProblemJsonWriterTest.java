package io.tenantkit.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract is classpath-independent by design (R4 consumer P0: Jackson 2
 * coupling broke Boot 4 consumers). Exact-bytes assertions instead of a parser.
 */
class TenantProblemJsonWriterTest {

    private final ProblemDetail detail = ProblemDetails.of(
            new io.tenantkit.core.exception.TenantMissingException("no tenant for /api/x"), 400);

    @Test
    void stableTopLevelFieldsAndShape() {
        String json = TenantProblemJsonWriter.write(detail, TenantKitErrorCodes.TENANT_MISSING);
        assertThat(json).startsWith("{").endsWith("}");
        assertThat(json).contains("\"type\":\"" + TenantKitErrorCodes.TYPE_URI_BASE + "TK-1001\"");
        assertThat(json).contains("\"title\":\"TK-1001\"");
        assertThat(json).contains("\"status\":400");
        assertThat(json).contains("\"detail\":\"no tenant for /api/x\"");
        assertThat(json).contains("\"errorCode\":\"TK-1001\"");
    }

    @Test
    void quotesBackslashesAndControlCharsAreEscaped() {
        ProblemDetail nasty = ProblemDetails.of(
                new io.tenantkit.core.exception.TenantMissingException("q\" b\\ n\nline\tend\u0001"), 400);
        String json = TenantProblemJsonWriter.write(nasty, "TK-1001");
        assertThat(json).contains("q\\\" b\\\\ n\\nline\\tend\\u0001");
        assertThat(json).doesNotContain("\n");
    }

    @Test
    void nullTitleFallsBackToStatusText() {
        ProblemDetail bare = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(404), "gone");
        String json = TenantProblemJsonWriter.write(bare, "TK-1002");
        assertThat(json).contains("\"status\":404");
        assertThat(json).contains("\"errorCode\":\"TK-1002\"");
    }

    @Test
    void problemDetailFactoryKeepsStableTypeAndStatus() {
        ProblemDetail d = ProblemDetails.of(
                new io.tenantkit.core.exception.UnknownTenantException("ghost"), HttpStatus.NOT_FOUND.value());
        assertThat(d.getType().toString()).endsWith(TenantKitErrorCodes.UNKNOWN_TENANT);
        assertThat(d.getStatus()).isEqualTo(404);
        assertThat(d.getProperties()).containsEntry("errorCode", TenantKitErrorCodes.UNKNOWN_TENANT);
    }
}
