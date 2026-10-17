package io.tenantkit.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.tenantkit.core.TenantContext;
import jakarta.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-servlet integration (R4 consumer P2 + scan-ordering fix): MockMvc does not
 * run the servlet filter chain, so mocked tests gave a different answer than a
 * real HTTP request. This suite boots an actual embedded tomcat and drives it
 * with plain JDK HttpClient — zero test-framework coupling, a guaranteed real
 * socket. Pins the end-to-end contract: filter registration at Ordered 0, the
 * startup scan of {@code @TenantPublic} paths (proven by a public endpoint
 * passing with NO tenant header), the TK-1001/TK-1002 wire shape, and the
 * post-handler advice path.
 */
@SpringBootTest(classes = TenantKitRealServerIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tenantkit.resolution.strategies=header",
                "tenantkit.resolution.trust-proxy=true",
                "tenantkit.tenants=alpha,beta"
        })
class TenantKitRealServerIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Resource
    private org.springframework.core.env.Environment environment;

    @LocalServerPort
    private int port;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AppController.class)
    static class App {
    }

    @RestController
    static class AppController {

        @TenantPublic
        @GetMapping("/public/echo")
        String publicEcho() {
            return "public:" + TenantContext.currentOrNull();
        }

        @GetMapping("/api/tenant")
        String tenantEcho() {
            return "api:" + TenantContext.required().value();
        }

        @TenantPublic
        @GetMapping("/public/advice-probe")
        String adviceProbe() {
            return "never:" + TenantContext.required().value();
        }
    }

    private HttpResponse<String> get(String path, String tenantHeader) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
        if (tenantHeader != null) {
            builder.header("X-Tenant-Id", tenantHeader);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void protectedEndpointWithoutTenantIs400WithTk1001() throws Exception {
        HttpResponse<String> response = get("/api/tenant", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body()).contains("\"errorCode\":\"TK-1001\"")
                .contains("\"status\":400")
                .contains("urn:tenantkit:errors:TK-1001");
    }

    @Test
    void protectedEndpointWithHeaderBindsTenant() throws Exception {
        HttpResponse<String> response = get("/api/tenant", "alpha");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("api:alpha");
    }

    @Test
    void lowercaseHeaderSpellingStillResolves() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/tenant"))
                .header("x-tenant-id", "beta").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("api:beta");
    }

    @Test
    void publicEndpointPassesWithoutTenantProvingTheStartupScanRan() throws Exception {
        // if the @TenantPublic scan had not registered (the pre-fix ordering trap:
        // tenantPublicScan is @ConditionalOnBean(RequestMappingHandlerMapping) and needs
        // the WebMvc autoconfig ordered before us), this request would be TK-1001
        HttpResponse<String> response = get("/public/echo", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("public:null");
    }

    @Test
    void publicEndpointStillResolvesWhenHeaderPresent() throws Exception {
        HttpResponse<String> response = get("/public/echo", "beta");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("public:beta");
    }

    @Test
    void unknownTenantIs404OnRealServer() throws Exception {
        HttpResponse<String> response = get("/api/tenant", "ghost");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"errorCode\":\"TK-1002\"");
    }

    @Test
    void runtimeMissingTenantInHandlerGoesThroughTheAdvice() throws Exception {
        // @TenantPublic path: no tenant bound, handler calls TenantContext.required()
        // -> post-handler advice path (distinct from the filter path) -> 400 ProblemDetail
        HttpResponse<String> response = get("/public/advice-probe", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errorCode\":\"TK-1001\"");
    }

    @Test
    void environmentSanity() {
        assertThat(environment.getProperty("tenantkit.resolution.trust-proxy")).isEqualTo("true");
        assertThat(port).isGreaterThan(0);
    }
}
