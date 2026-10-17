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
 * Always resolves the configured fixed tenant. Requires {@code tenantkit.resolution.fixed}
 * to be set; the value is validated through {@link TenantId} like any other source.
 */
public final class FixedTenantResolver implements TenantResolver {

    private final TenantId fixed;
    private final int order;

    public FixedTenantResolver(TenantKitResolutionProperties properties, int order) {
        this.fixed = TenantId.of(properties.getFixed());
        this.order = order;
    }

    @Override
    public Optional<TenantId> resolve(TenantResolutionRequest request) {
        return Optional.of(fixed);
    }

    @Override
    public int order() {
        return order;
    }
}
