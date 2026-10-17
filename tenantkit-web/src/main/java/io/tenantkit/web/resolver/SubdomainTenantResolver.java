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
import java.util.Locale;
import java.util.Optional;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;

/**
 * Resolves {@code <tenant>.<base-domain>} host names. Matching happens on the
 * port-stripped, lower-cased hostname; multi-level nesting
 * ({@code a.b.<base>}) and mixed-case hosts are rejected, and base domains
 * configured with a port fail startup (port ambiguity, DESIGN &sect;6.2).
 * Unknown sub-domains surface as TK-1002.
 */
public final class SubdomainTenantResolver implements TenantResolver {

    private final List<String> baseDomains;
    private final int order;

    public SubdomainTenantResolver(List<String> baseDomains, int order) {
        this.baseDomains = baseDomains.stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .toList();
        this.order = order;
    }

    @Override
    public Optional<TenantId> resolve(TenantResolutionRequest request) {
        String host = request.host();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String hostname = host;
        int portSeparator = hostname.indexOf(':');
        if (portSeparator >= 0) {
            hostname = hostname.substring(0, portSeparator);
        }
        // mixed-case hosts are rejected, not normalized: an inconsistent boundary is a
        // signal, and silently lower-casing would widen who we trust (DESIGN §6.2)
        if (!hostname.equals(hostname.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        for (String base : baseDomains) {
            String suffix = "." + base;
            if (hostname.endsWith(suffix) && hostname.length() > suffix.length()) {
                String tenant = hostname.substring(0, hostname.length() - suffix.length());
                if (tenant.contains(".")) {
                    // a.b.base matches two levels; only a single label may prefix the base
                    return Optional.empty();
                }
                return Optional.of(TenantId.of(tenant));
            }
        }
        return Optional.empty();
    }

    @Override
    public int order() {
        return order;
    }
}
