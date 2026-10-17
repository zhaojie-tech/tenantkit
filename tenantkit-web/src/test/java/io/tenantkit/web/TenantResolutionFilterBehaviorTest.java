package io.tenantkit.web;

import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.core.catalog.ConfigTenantCatalog;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import io.tenantkit.web.resolver.FixedTenantResolver;
import io.tenantkit.web.resolver.JwtClaimTenantResolver;
import io.tenantkit.web.resolver.HeaderTenantResolver;
import io.tenantkit.web.resolver.SubdomainTenantResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior-layer matrix cases W09–W42 from docs/matrix/assembly-matrix.md
 * (frozen). The filter is constructed directly; servlet mocks come from
 * spring-test, no real container needed (container-level end-to-end belongs
 * to the M2/M3 integration closure).
 */
class TenantResolutionFilterBehaviorTest {

    private static final String HEADER = "X-Tenant-Id";

    private final AtomicReference<TenantId> seenInChain = new AtomicReference<>();

    private final FilterChain chain = (request, response) -> seenInChain.set(TenantContext.currentOrNull());

    @AfterEach
    void resetContextAndPattern() {
        seenInChain.set(null);
        MDC.remove(TenantResolutionFilter.MDC_KEY);
        TenantId.resetPattern();
    }

    // ---- helpers -------------------------------------------------------

    private TenantKitResolutionProperties properties(TenantKitResolutionProperties.Strategy... strategies) {
        TenantKitResolutionProperties properties = new TenantKitResolutionProperties();
        properties.setStrategies(List.of(strategies));
        return properties;
    }

    private TenantResolutionFilter filter(TenantKitResolutionProperties properties, TenantResolver... resolvers) {
        return new TenantResolutionFilter(List.of(resolvers), properties, null, null);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private MockHttpServletResponse doFilter(TenantResolutionFilter filter, HttpServletRequest request)
            throws ServletException, java.io.IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private String body(MockHttpServletResponse response) throws UnsupportedEncodingException {
        return response.getContentAsString();
    }

    private TenantCatalog catalog(String... tenants) {
        return new ConfigTenantCatalog(java.util.Arrays.stream(tenants).map(TenantId::of).toList());
    }

    private RecordingResolver recording(Optional<String> value, int order) {
        return new RecordingResolver(value, order);
    }

    // ---- group B: strategy composition & priority ----------------------

    /** W50 — the filter registers at Ordered 0, after the Security chain (-100) and before default filters. */
    @Test
    void w50_filterOrderIsZero() {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.FIXED);
        properties.setFixed("alpha");
        TenantResolutionFilter filter = filter(properties, new FixedTenantResolver(properties, 0));
        assertThat(filter.getOrder()).isEqualTo(TenantResolutionFilter.DEFAULT_ORDER);
    }

    /** W09 — fixed resolves its value. */
    @Test
    void w09_fixedResolvesValue() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.FIXED);
        properties.setFixed("alpha");
        MockHttpServletResponse response = doFilter(
                filter(properties, new FixedTenantResolver(properties, 0)), request("/api/x"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("alpha"));
    }

    /** W10 — header strategy resolves the header value when trust-proxy is on. */
    @Test
    void w10_headerResolvesHeaderValue() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "beta");
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("beta"));
    }

    /** W11 — header, fixed: missing header falls through to fixed. */
    @Test
    void w11_headerThenFixedFallsThroughToFixed() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.HEADER, TenantKitResolutionProperties.Strategy.FIXED);
        properties.setFixed("gamma");
        MockHttpServletResponse response = doFilter(
                filter(properties, new HeaderTenantResolver(properties, 0), new FixedTenantResolver(properties, 100)),
                request("/api/x"));
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("gamma"));
    }

    /** W12 — header, fixed: header wins when present. */
    @Test
    void w12_headerWinsWhenPresent() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.HEADER, TenantKitResolutionProperties.Strategy.FIXED);
        properties.setFixed("gamma");
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "beta");
        doFilter(filter(properties, new HeaderTenantResolver(properties, 0), new FixedTenantResolver(properties, 100)),
                request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("beta"));
    }

    /** W13 — fixed, header: fixed wins despite present header (order is authority). */
    @Test
    void w13_fixedWinsWhenOrderedFirst() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.FIXED, TenantKitResolutionProperties.Strategy.HEADER);
        properties.setFixed("gamma");
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "beta");
        doFilter(filter(properties, new FixedTenantResolver(properties, 0), new HeaderTenantResolver(properties, 100)),
                request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("gamma"));
    }

    /** W14 — jwt-claim, header: verified claim wins over the header. */
    @Test
    void w14_jwtClaimWinsOverHeader() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.JWT_CLAIM, TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "beta");
        request.setUserPrincipal(new JwtAuthenticationToken(verifiedJwt("alpha")));
        doFilter(filter(properties, new JwtClaimTenantResolver("tenant_id", 0),
                new HeaderTenantResolver(properties, 100)), request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("alpha"));
    }

    /** W15 — jwt-claim, header: anonymous request falls through to the header. */
    @Test
    void w15_anonymousFallsThroughToHeader() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.JWT_CLAIM, TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "beta");
        doFilter(filter(properties, new JwtClaimTenantResolver("tenant_id", 0),
                new HeaderTenantResolver(properties, 100)), request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("beta"));
    }

    /** W16 — subdomain match resolves the tenant label. */
    @Test
    void w16_subdomainMatchResolves() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.SUBDOMAIN);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("tenant1.saas.example.com");
        doFilter(filter(properties, new SubdomainTenantResolver(
                properties.getSubdomain().getBaseDomains(), 0)), request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("tenant1"));
    }

    /** W17 — bare base domain does not resolve -> TK-1001. */
    @Test
    void w17_bareBaseDomainDoesNotResolve() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.SUBDOMAIN);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("saas.example.com");
        MockHttpServletResponse response = doFilter(filter(properties, new SubdomainTenantResolver(
                properties.getSubdomain().getBaseDomains(), 0)), request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1001");
    }

    /** W18 — foreign host does not resolve. */
    @Test
    void w18_foreignHostDoesNotResolve() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.SUBDOMAIN);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("tenant1.other.com");
        MockHttpServletResponse response = doFilter(filter(properties, new SubdomainTenantResolver(
                properties.getSubdomain().getBaseDomains(), 0)), request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1001");
    }

    /** W20 — subdomain miss falls through to header. */
    @Test
    void w20_subdomainMissFallsThroughToHeader() throws Exception {
        TenantKitResolutionProperties properties = properties(
                TenantKitResolutionProperties.Strategy.SUBDOMAIN, TenantKitResolutionProperties.Strategy.HEADER);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("tenant1.other.com");
        request.addHeader(HEADER, "beta");
        doFilter(filter(properties, new SubdomainTenantResolver(properties.getSubdomain().getBaseDomains(), 0),
                new HeaderTenantResolver(properties, 100)), request);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("beta"));
    }

    /** W21 — multi-level nesting a.b.base is rejected. */
    @Test
    void w21_multiLevelNestingRejected() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.SUBDOMAIN);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("a.b.saas.example.com");
        MockHttpServletResponse response = doFilter(filter(properties, new SubdomainTenantResolver(
                properties.getSubdomain().getBaseDomains(), 0)), request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1001");
    }

    /** W23 — mixed-case host is rejected. */
    @Test
    void w23_mixedCaseHostRejected() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.SUBDOMAIN);
        properties.getSubdomain().getBaseDomains().add("saas.example.com");
        MockHttpServletRequest request = request("/api/x");
        request.setServerName("Tenant1.saas.example.com");
        MockHttpServletResponse response = doFilter(filter(properties, new SubdomainTenantResolver(
                properties.getSubdomain().getBaseDomains(), 0)), request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1001");
    }

    // ---- group C: charset validation & error codes ----------------------

    /** W24 — valid id passes the default charset. */
    @Test
    void w24_validIdPasses() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "alpha");
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("alpha"));
    }

    /** W25 — uppercase violates the charset -> 400 TK-1003 ProblemDetail. */
    @Test
    void w25_uppercaseIdRejectedAsTk1003() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "UPPER");
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(body(response)).contains("TK-1003");
        assertThat(seenInChain.get()).isNull();
    }

    /** W26 — whitespace violates the charset. */
    @Test
    void w26_whitespaceIdRejected() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "a b");
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1003");
    }

    /** W27 — overlong id rejected. */
    @Test
    void w27_overlongIdRejected() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "a".repeat(64));
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1003");
    }

    /** W28 — relaxed pattern accepts mixed case. */
    @Test
    void w28_relaxedPatternAcceptsConfigured() throws Exception {
        TenantId.usePattern("[a-zA-Z0-9]{1,16}");
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "Alpha");
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("Alpha"));
    }

    /** W29 — no source at all -> 400 TK-1001 ProblemDetail. */
    @Test
    void w29_missingTenantIsTk1001() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request("/api/x"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(body(response)).contains("TK-1001");
    }

    /** W30 — resolvable id outside the catalog -> TK-1002 with default 404. */
    @Test
    void w30_unknownTenantIsTk1002WithDefault404() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "ghost");
        TenantResolutionFilter filter = new TenantResolutionFilter(
                List.of(new HeaderTenantResolver(properties, 0)), properties, null, catalog("alpha", "beta"));
        MockHttpServletResponse response = doFilter(filter, request);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response)).contains("TK-1002");
    }

    /** W31 — unknown-tenant-status=400 overrides the default. */
    @Test
    void w31_unknownTenantStatusConfigurable() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        properties.setUnknownTenantStatus(400);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "ghost");
        TenantResolutionFilter filter = new TenantResolutionFilter(
                List.of(new HeaderTenantResolver(properties, 0)), properties, null, catalog("alpha", "beta"));
        MockHttpServletResponse response = doFilter(filter, request);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1002");
    }

    // ---- group D: exemptions ---------------------------------------------

    /** W32 — ignore-paths short-circuits before resolution. */
    @Test
    void w32_ignorePathsShortCircuitBeforeResolution() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        properties.getIgnorePaths().add("/actuator/**");
        RecordingResolver recorder = recording(Optional.empty(), 0);
        MockHttpServletResponse response = doFilter(filter(properties, recorder), request("/actuator/health"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recorder.called()).as("ignore-paths must not invoke resolvers").isEmpty();
        assertThat(seenInChain.get()).isNull();
    }

    /** W33 — @TenantPublic path: missing tenant suppressed, context stays empty. */
    @Test
    void w33_tenantPublicSuppressesMissingFailure() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        TenantResolutionFilter filter = new TenantResolutionFilter(
                List.of(new HeaderTenantResolver(properties, 0)), properties,
                pathSet("/public/echo"), null);
        MockHttpServletResponse response = doFilter(filter, request("/public/echo"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).as("public path still leaves the context empty").isNull();
    }

    /** W34 — @TenantPublic path still resolves when a tenant is present. */
    @Test
    void w34_tenantPublicStillResolves() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        TenantResolutionFilter filter = new TenantResolutionFilter(
                List.of(new HeaderTenantResolver(properties, 0)), properties,
                pathSet("/public/echo"), null);
        MockHttpServletRequest request = request("/public/echo");
        request.addHeader(HEADER, "alpha");
        MockHttpServletResponse response = doFilter(filter, request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("alpha"));
    }

    /** W35 — ignore-paths beats @TenantPublic on double match (no resolution at all). */
    @Test
    void w35_ignorePathsBeatsTenantPublic() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        properties.getIgnorePaths().add("/public/**");
        RecordingResolver recorder = recording(Optional.empty(), 0);
        TenantResolutionFilter filter = new TenantResolutionFilter(List.of(recorder), properties,
                pathSet("/public/echo"), null);
        MockHttpServletResponse response = doFilter(filter, request("/public/echo"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recorder.called()).as("ignore-paths precedence means zero resolution").isEmpty();
    }

    /** W37 — non-exempt path without tenant -> TK-1001 (control negative). */
    @Test
    void w37_nonExemptPathStillFails() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        TenantResolutionFilter filter = new TenantResolutionFilter(
                List.of(new HeaderTenantResolver(properties, 0)), properties,
                pathSet("/public/echo"), null);
        MockHttpServletResponse response = doFilter(filter, request("/api/x"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).contains("TK-1001");
    }

    // ---- group E: snapshot & SPI ----------------------------------------

    /** W38 — the resolver receives a faithful request snapshot. */
    @Test
    void w38_snapshotCarriesRequestFacts() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        AtomicReference<TenantResolutionRequest> captured = new AtomicReference<>();
        TenantResolver capturing = new TenantResolver() {
            @Override
            public Optional<TenantId> resolve(TenantResolutionRequest requestSnapshot) {
                captured.set(requestSnapshot);
                return Optional.empty();
            }

            @Override
            public int order() {
                return 0;
            }
        };
        MockHttpServletRequest request = request("/api/list");
        request.setServerName("acme.example.com");
        request.addHeader(HEADER, "alpha");
        doFilter(filter(properties, capturing), request);
        TenantResolutionRequest snapshot = captured.get();
        assertThat(snapshot.requestUri().toString()).endsWith("/api/list");
        assertThat(snapshot.host()).isEqualTo("acme.example.com");
        assertThat(snapshot.headers().getFirst(HEADER)).isEqualTo("alpha");
        assertThat(snapshot.authentication()).isNull();
    }

    /** W39 — chain order follows order(), lower first. */
    @Test
    void w39_orderDeterminesChainSequence() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        List<Integer> sequence = new ArrayList<>();
        TenantResolver ordered200 = new TenantResolver() {
            @Override
            public Optional<TenantId> resolve(TenantResolutionRequest request) {
                sequence.add(200);
                return Optional.empty();
            }

            @Override
            public int order() {
                return 200;
            }
        };
        TenantResolver ordered100 = new TenantResolver() {
            @Override
            public Optional<TenantId> resolve(TenantResolutionRequest request) {
                sequence.add(100);
                return Optional.empty();
            }

            @Override
            public int order() {
                return 100;
            }
        };
        doFilter(filter(properties, ordered200, ordered100), request("/api/x"));
        assertThat(sequence).containsExactly(100, 200);
    }

    /** W40 — first non-empty result wins. */
    @Test
    void w40_firstNonEmptyWins() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        RecordingResolver emptyFirst = recording(Optional.empty(), 0);
        RecordingResolver valueSecond = recording(Optional.of("winner"), 100);
        doFilter(filter(properties, emptyFirst, valueSecond), request("/api/x"));
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("winner"));
        assertThat(emptyFirst.called()).hasSize(1);
    }

    /** W41 — multi-value headers pass through the snapshot completely. */
    @Test
    void w41_multiValueHeadersPassThrough() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        AtomicReference<TenantResolutionRequest> captured = new AtomicReference<>();
        TenantResolver capturing = new TenantResolver() {
            @Override
            public Optional<TenantId> resolve(TenantResolutionRequest requestSnapshot) {
                captured.set(requestSnapshot);
                return Optional.empty();
            }

            @Override
            public int order() {
                return 0;
            }
        };
        MockHttpServletRequest request = request("/api/x");
        request.addHeader("Accept", "text/html");
        request.addHeader("Accept", "application/json");
        doFilter(filter(properties, capturing), request);
        assertThat(captured.get().headers().get("Accept"))
                .containsExactly("text/html", "application/json");
    }

    /** W42 — Principal is the JDK type: custom instance passes through, anonymous stays null. */
    @Test
    void w42_principalIsPlainJdkType() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        Principal custom = () -> "custom-user";
        AtomicReference<TenantResolutionRequest> captured = new AtomicReference<>();
        TenantResolver capturing = new TenantResolver() {
            @Override
            public Optional<TenantId> resolve(TenantResolutionRequest requestSnapshot) {
                captured.set(requestSnapshot);
                return Optional.empty();
            }

            @Override
            public int order() {
                return 0;
            }
        };
        MockHttpServletRequest request = request("/api/x");
        request.setUserPrincipal(custom);
        doFilter(filter(properties, capturing), request);
        assertThat(captured.get().authentication()).isSameAs(custom);
    }

    /** R2 fix lock-in: downstream checked exceptions propagate unchanged (container /error semantics). */
    @Test
    void downstreamIOExceptionPropagatesUnchanged() {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        jakarta.servlet.FilterChain throwing = (req, res) -> {
            throw new java.io.IOException("broken pipe");
        };
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "alpha"); // reach chain.doFilter: resolution must succeed first
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter(properties, new HeaderTenantResolver(properties, 0))
                                .doFilter(request, response, throwing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("broken pipe");
        // and the context is still restored on that path
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void downstreamServletExceptionPropagatesUnchanged() {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        jakarta.servlet.FilterChain throwing = (req, res) -> {
            throw new jakarta.servlet.ServletException("handler boom");
        };
        MockHttpServletRequest servletExRequest = request("/api/x");
        servletExRequest.addHeader(HEADER, "alpha"); // reach chain.doFilter first
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter(properties, new HeaderTenantResolver(properties, 0))
                                .doFilter(servletExRequest, response, throwing))
                .isInstanceOf(jakarta.servlet.ServletException.class)
                .hasMessage("handler boom");
    }

    /** R2 fix lock-in: lowercase header name still matches (HTTP names are case-insensitive). */
    @Test
    void lowercaseHeaderNameStillResolves() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        MockHttpServletRequest request = request("/api/x");
        request.addHeader("x-tenant-id", "alpha"); // lowercase variant of X-Tenant-Id
        MockHttpServletResponse response = doFilter(filter(properties, new HeaderTenantResolver(properties, 0)),
                request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenInChain.get()).isEqualTo(TenantId.of("alpha"));
    }

    /** Bonus (§7): MDC carries the tenant during the chain and is removed after. */
    @Test
    void mdcCarriesTenantDuringRequestAndCleansUp() throws Exception {
        TenantKitResolutionProperties properties = properties(TenantKitResolutionProperties.Strategy.HEADER);
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        FilterChain mdcChain = (req, res) -> {
            mdcSeen.set(MDC.get(TenantResolutionFilter.MDC_KEY));
            seenInChain.set(TenantContext.currentOrNull());
        };
        MockHttpServletRequest request = request("/api/x");
        request.addHeader(HEADER, "alpha");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(properties, new HeaderTenantResolver(properties, 0)).doFilter(request, response, mdcChain);
        assertThat(mdcSeen.get()).isEqualTo("alpha");
        assertThat(MDC.get(TenantResolutionFilter.MDC_KEY)).isNull();
    }

    private TenantResolutionFilter.TenantPublicPaths pathSet(String... paths) {
        Set<String> set = Set.of(paths);
        return set::contains;
    }

    private Jwt verifiedJwt(String tenantClaim) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("tenant_id", tenantClaim)
                .build();
    }
}
