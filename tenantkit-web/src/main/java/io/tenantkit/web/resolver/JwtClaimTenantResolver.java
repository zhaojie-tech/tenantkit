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
package io.tenantkit.web.resolver;

import java.util.Optional;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.web.properties.TenantKitResolutionProperties;

/**
 * Reads the tenant claim from an <em>already verified</em> JWT carried by
 * Spring Security's {@code JwtAuthenticationToken} (DESIGN &sect;6.2). Raw
 * Authorization-header parsing is forbidden inside the kit (ArchUnit rule):
 * an unverified claim is an open door, not a tenant source. Assembly requires
 * Spring Security on the classpath &mdash; otherwise startup fails fast
 * instead of silently degrading.
 *
 * <p>Only {@code JwtAuthenticationToken} is supported; opaque tokens must go
 * through {@code header + trust-proxy} or a custom {@link TenantResolver}.
 */
public final class JwtClaimTenantResolver implements TenantResolver {

    private final String claimName;
    private final int order;

    public JwtClaimTenantResolver(String claimName, int order) {
        this.claimName = claimName;
        this.order = order;
    }

    @Override
    public Optional<TenantId> resolve(TenantResolutionRequest request) {
        if (request.authentication() == null) {
            return Optional.empty();
        }
        // JwtAuthenticationToken implements Principal; reflection-free access requires
        // the security classes, which the auto-configuration guarantees to be present
        return JwtClaimReader.read(request.authentication(), claimName);
    }

    @Override
    public int order() {
        return order;
    }
}
