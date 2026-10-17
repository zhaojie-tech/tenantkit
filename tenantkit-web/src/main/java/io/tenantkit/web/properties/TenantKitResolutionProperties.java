/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.web.properties;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Resolution configuration ({@code tenantkit.resolution.*}, DESIGN &sect;6.2).
 * Strategy list order is the priority order: the first resolver in the list
 * that yields a non-empty tenant wins.
 */
@ConfigurationProperties(prefix = "tenantkit.resolution")
public class TenantKitResolutionProperties {

    /** Built-in strategy identifiers (value domain of {@code strategies}). */
    public enum Strategy {
        /** Verified JWT claim from Spring Security's authentication. */
        JWT_CLAIM("jwt-claim"),
        /** {@code X-Tenant-Id} header; requires {@code trust-proxy: true}. */
        HEADER("header"),
        /** {@code <tenant>.<base-domain>} host matching. */
        SUBDOMAIN("subdomain"),
        /** Static value, for single-tenant or internal deployments. */
        FIXED("fixed");

        private final String key;

        Strategy(String key) {
            this.key = key;
        }

        /** Property-facing identifier, e.g. {@code jwt-claim}. */
        public String key() {
            return key;
        }
    }

    /** Ordered strategy chain; empty = module resolves nothing (assembly backs off). */
    private List<Strategy> strategies = new ArrayList<>();

    /** Fixed tenant value; required when {@code strategies} contains {@code fixed}. */
    private String fixed;

    /** Tenant-id charset regex; relaxing it re-opens an injection surface (DESIGN §6.5). */
    private String tenantIdPattern = "[a-z0-9-]{1,32}";

    /** Claim name read from the verified JWT. */
    private String jwtClaim = "tenant_id";

    /** Header name inspected by the {@code header} strategy. */
    private String headerName = "X-Tenant-Id";

    /**
     * Whether a directly-connected client may set the tenant header. Default
     * {@code false}: declaring {@code header} with this off fails startup.
     */
    private boolean trustProxy = false;

    /** Base domains for the {@code subdomain} strategy; required for it. */
    private Subdomain subdomain = new Subdomain();

    /** Paths resolved by no one: filter-level short-circuit (no resolution, no failure). */
    private List<String> ignorePaths = new ArrayList<>();

    /** HTTP status for TK-1002 unknown tenant (default 404). */
    private int unknownTenantStatus = 404;

    /** Sub-domain segment. */
    public static class Subdomain {

        /** Base domains, e.g. {@code saas.example.com}. Ports are rejected as ambiguous. */
        private List<String> baseDomains = new ArrayList<>();

        public List<String> getBaseDomains() {
            return baseDomains;
        }

        public void setBaseDomains(List<String> baseDomains) {
            this.baseDomains = baseDomains;
        }
    }

    public List<Strategy> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<Strategy> strategies) {
        this.strategies = strategies;
    }

    public String getFixed() {
        return fixed;
    }

    public void setFixed(String fixed) {
        this.fixed = fixed;
    }

    public String getTenantIdPattern() {
        return tenantIdPattern;
    }

    public void setTenantIdPattern(String tenantIdPattern) {
        this.tenantIdPattern = tenantIdPattern;
    }

    public String getJwtClaim() {
        return jwtClaim;
    }

    public void setJwtClaim(String jwtClaim) {
        this.jwtClaim = jwtClaim;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public boolean isTrustProxy() {
        return trustProxy;
    }

    public void setTrustProxy(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    public Subdomain getSubdomain() {
        return subdomain;
    }

    public void setSubdomain(Subdomain subdomain) {
        this.subdomain = subdomain;
    }

    public List<String> getIgnorePaths() {
        return ignorePaths;
    }

    public void setIgnorePaths(List<String> ignorePaths) {
        this.ignorePaths = ignorePaths;
    }

    public int getUnknownTenantStatus() {
        return unknownTenantStatus;
    }

    public void setUnknownTenantStatus(int unknownTenantStatus) {
        this.unknownTenantStatus = unknownTenantStatus;
    }

    /** {@code true} when the given strategy is part of the configured chain. */
    public boolean hasStrategy(Strategy strategy) {
        return strategies.contains(strategy);
    }

    /** {@code true} when no strategy is configured (module resolves nothing). */
    public boolean isEmpty() {
        return strategies.isEmpty();
    }

    /**
     * Canonical form used for strategy matching, mirroring Spring Boot's lenient
     * enum converter ({@code LenientObjectToEnumConverterFactory}): keep letters
     * and digits only, then lowercase. {@code "JWT-Claim"}, {@code "jwt_claim"}
     * and {@code "jwtclaim"} all canonicalize to {@code "jwtclaim"} — identity with
     * the binding path, so a spelling that binds can never silently skip the
     * fail-fast guards or the resolver registration.
     */
    public static String canonicalStrategy(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
