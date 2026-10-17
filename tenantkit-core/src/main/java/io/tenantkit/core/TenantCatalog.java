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
package io.tenantkit.core;

import java.util.List;

/**
 * Source of the active tenant list (DESIGN &sect;6.1, R1-P0: core cannot
 * enumerate tenants by itself). The built-in implementation reads the static
 * {@code tenantkit.tenants} property; a DB-backed implementation is user code
 * (the docs example mirrors the production pattern and carries the lesson:
 * the tenant directory table itself must be exempt from any future row-level
 * filter &mdash; see ADR-0002).
 */
public interface TenantCatalog {

    /**
     * The active tenants. Implementations should return a stable order (the
     * built-in config implementation preserves declaration order) because
     * {@code Tenants.forEach} iterates it.
     */
    List<TenantId> activeTenants();
}
