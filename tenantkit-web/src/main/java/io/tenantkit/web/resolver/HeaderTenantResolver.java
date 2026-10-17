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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.web.properties.TenantKitResolutionProperties;

/**
 * Reads the tenant from a request header. Registered only when
 * {@code tenantkit.resolution.trust-proxy: true} &mdash; a directly connected
 * client can set arbitrary headers, so trusting them requires a boundary
 * (gateway) that strips inbound tenant headers first (DESIGN &sect;6.2).
 */
public final class HeaderTenantResolver implements TenantResolver {

    private final TenantKitResolutionProperties properties;
    private final int order;

    public HeaderTenantResolver(TenantKitResolutionProperties properties, int order) {
        this.properties = properties;
        this.order = order;
    }

    @Override
    public Optional<TenantId> resolve(TenantResolutionRequest request) {
        // HTTP header names are case-insensitive but the snapshot preserves the
        // on-the-wire casing; match ignoring case or lowercase "x-tenant-id"
        // from plain clients/caches misses (R1 architecture review P1-10)
        String configured = properties.getHeaderName();
        String value = request.headers().getFirst(configured);
        if (value == null) {
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                if (configured.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                    value = entry.getValue().get(0);
                    break;
                }
            }
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(TenantId.of(value.trim()));
    }

    @Override
    public int order() {
        return order;
    }
}
