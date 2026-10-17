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
package io.tenantkit.core.catalog;

import java.util.List;
import java.util.Objects;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantId;

/**
 * Built-in {@link TenantCatalog} backed by the static {@code tenantkit.tenants}
 * property list. Preserves declaration order.
 */
public final class ConfigTenantCatalog implements TenantCatalog {

    private final List<TenantId> tenants;

    public ConfigTenantCatalog(List<TenantId> tenants) {
        Objects.requireNonNull(tenants, "tenants must not be null");
        if (tenants.isEmpty()) {
            throw new IllegalArgumentException("tenantkit.tenants must declare at least one tenant");
        }
        this.tenants = List.copyOf(tenants);
    }

    @Override
    public List<TenantId> activeTenants() {
        return tenants;
    }

    @Override
    public String toString() {
        return "ConfigTenantCatalog" + tenants;
    }
}
