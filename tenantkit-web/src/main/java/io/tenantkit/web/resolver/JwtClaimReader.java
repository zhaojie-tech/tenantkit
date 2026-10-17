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

import java.security.Principal;
import java.util.Optional;

import io.tenantkit.core.TenantId;

/**
 * Claim extraction from a verified {@code JwtAuthenticationToken}. Isolated in
 * this class so {@link JwtClaimTenantResolver} stays loadable without Spring
 * Security on the classpath (the auto-configuration only registers the pair
 * when Security is present).
 */
final class JwtClaimReader {

    private JwtClaimReader() {
    }

    static Optional<TenantId> read(Principal authentication, String claimName) {
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            Object claim = jwt.getToken().getClaims().get(claimName);
            if (claim instanceof String value && !value.isBlank()) {
                return Optional.of(TenantId.of(value.trim()));
            }
        }
        return Optional.empty();
    }
}
