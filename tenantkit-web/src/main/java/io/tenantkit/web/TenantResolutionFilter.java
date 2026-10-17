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
package io.tenantkit.web;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantKitException;
import io.tenantkit.core.exception.TenantMissingException;
import io.tenantkit.core.exception.UnknownTenantException;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the tenant per request and binds it to {@link TenantContext} for
 * the remainder of the chain (DESIGN &sect;6.2).
 *
 * <p>Decision sequence: {@code ignore-paths} short-circuit (no resolution, no
 * failure) &rarr; resolver chain (first non-empty wins) &rarr; TK-1003 on
 * charset violation &rarr; catalog validation (TK-1002) &rarr; missing tenant
 * suppressed on {@code @TenantPublic} paths, TK-1001 otherwise. The filter
 * defaults to order 0 &mdash; after Spring Security's default chain (-100) so
 * {@code JwtClaimTenantResolver} sees already-verified claims.
 */
public class TenantResolutionFilter extends OncePerRequestFilter implements org.springframework.core.Ordered {

    /** MDC key injected for the duration of the request. */
    public static final String MDC_KEY = "tenantId";

    /** Default order: after the Spring Security filter chain (-100). */
    public static final int DEFAULT_ORDER = 0;

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    private final List<TenantResolver> resolvers;
    private final TenantKitResolutionProperties properties;
    private final PathMatcher ignorePathMatcher = new AntPathMatcher();
    private final List<String> ignorePaths;
    private final TenantPublicPaths tenantPublicPaths;
    private final TenantCatalog catalog;

    public TenantResolutionFilter(List<TenantResolver> resolvers, TenantKitResolutionProperties properties,
            TenantPublicPaths tenantPublicPaths, TenantCatalog catalog) {
        this.resolvers = resolvers.stream()
                .sorted(java.util.Comparator.comparingInt(TenantResolver::order))
                .toList();
        this.properties = properties;
        this.ignorePaths = List.copyOf(properties.getIgnorePaths());
        this.tenantPublicPaths = tenantPublicPaths;
        this.catalog = catalog;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String lookupPath = lookupPath(request);
        if (ignorePaths.stream().anyMatch(pattern -> ignorePathMatcher.match(pattern, lookupPath))) {
            chain.doFilter(request, response);
            return;
        }

        TenantResolutionRequest snapshot = snapshot(request);
        TenantId tenant = null;
        try {
            for (TenantResolver resolver : resolvers) {
                Optional<TenantId> resolved = resolver.resolve(snapshot);
                if (resolved.isPresent()) {
                    tenant = resolved.get();
                    break;
                }
            }
        }
        catch (TenantIdFormatException ex) {
            writeProblem(response, ex, 400);
            return;
        }

        if (tenant == null) {
            if (tenantPublicPaths != null && tenantPublicPaths.isPublic(lookupPath)) {
                chain.doFilter(request, response);
                return;
            }
            writeProblem(response,
                    new TenantMissingException("no tenant could be resolved for " + request.getRequestURI()), 400);
            return;
        }

        if (catalog != null && !catalog.activeTenants().contains(tenant)) {
            writeProblem(response, new UnknownTenantException(
                    "tenant '" + tenant.value() + "' is not a known tenant"), properties.getUnknownTenantStatus());
            return;
        }

        final TenantId resolvedTenant = tenant;
        final Throwable[] downstream = new Throwable[1];
        TenantContext.runAs(resolvedTenant, () -> {
            MDC.put(MDC_KEY, resolvedTenant.value());
            try {
                chain.doFilter(request, response);
            }
            catch (ServletException | IOException | RuntimeException ex) {
                downstream[0] = ex;
            }
            finally {
                MDC.remove(MDC_KEY);
            }
        });
        // rethrow the downstream exception unchanged: the container's error
        // semantics (ErrorController, /error mapping) must stay intact
        if (downstream[0] instanceof ServletException servletException) {
            throw servletException;
        }
        if (downstream[0] instanceof IOException ioException) {
            throw ioException;
        }
        if (downstream[0] instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    private String lookupPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() || !uri.startsWith(context) ? uri : uri.substring(context.length());
    }

    private TenantResolutionRequest snapshot(HttpServletRequest request) {
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, Collections.list(request.getHeaders(name))));
        Principal principal = request.getUserPrincipal();
        return new TenantResolutionRequest(URI.create(request.getRequestURL().toString()),
                request.getServerName(), headers, principal);
    }

    private void writeProblem(HttpServletResponse response, TenantKitException exception, int status)
            throws IOException {
        ProblemDetail detail = ProblemDetails.of(exception, status);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(TenantProblemJsonWriter.write(detail, exception.errorCode()));
    }

    /** Immutable set of {@code @TenantPublic} path patterns (startup-scanned). */
    public interface TenantPublicPaths {

        boolean isPublic(String lookupPath);
    }
}
