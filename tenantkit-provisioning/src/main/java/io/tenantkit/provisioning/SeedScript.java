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
package io.tenantkit.provisioning;

import io.tenantkit.core.TenantId;

/**
 * One located seed script with its ledger identity already computed.
 *
 * @param name script file name, e.g. {@code V1__insert_core_data.sql} &mdash;
 *        the ledger's {@code script_name} column value
 * @param sequence the {@code V<n>} prefix number, defining execution order
 * @param sql line-ending-normalized script content; {@code ${tenantId}} is
 *        replaced at execution time
 * @param checksum ledger checksum over {@code name + normalized sql}
 */
record SeedScript(String name, int sequence, String sql, String checksum) {

    /**
     * Script content with the {@code ${tenantId}} placeholder replaced.
     * Injection-safe only while the tenant id charset stays
     * {@code [a-z0-9-]} (default) &mdash; widening
     * {@code tenantkit.resolution.tenant-id-pattern} re-opens the surface and
     * requires parameterized binding or allow-list escaping here (DESIGN
     * &sect;6.5, risk R3).
     */
    String sqlFor(TenantId tenant) {
        return sql.replace("${tenantId}", tenant.value());
    }
}
