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
package io.tenantkit.provisioning.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the provisioning module (prefix
 * {@code tenantkit.provisioning}, DESIGN &sect;6.5). {@code ProvisionOptions}
 * overrides these per call; a {@code null} option field falls back to the
 * value bound here.
 */
@ConfigurationProperties(prefix = "tenantkit.provisioning")
public class TenantKitProvisioningProperties {

    /** Master switch; {@code false} disables the module's auto-configuration entirely. */
    private boolean enabled = true;

    /** Base location of seed scripts; a profile is {@code <location>/<name>/V<n>__*.sql}. */
    private String seedLocation = "classpath:db/tenant-seed/";

    /** Seed ledger table (global table; migrated by the application, see {@code SeedLedger}). */
    private String ledgerTable = "tenantkit_seed_history";

    /**
     * How long the tenant provisioning lock ({@code GET_LOCK}) blocks before
     * the run fails with TK-2002. The lock is acquired on a dedicated
     * connection held for the whole run and always released in a
     * {@code finally} block (ADR-0001); negative values would wait forever on
     * MySQL 5.7.5+ &mdash; keep this finite.
     */
    private int lockTimeoutSeconds = 30;

    /**
     * Audit-only mode: parse scripts, replace placeholders and log what would
     * run; nothing executes, nothing is recorded. Overridable per call via
     * {@code ProvisionOptions.dryRun()}.
     */
    private boolean dryRun = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSeedLocation() {
        return seedLocation;
    }

    public void setSeedLocation(String seedLocation) {
        this.seedLocation = seedLocation;
    }

    public String getLedgerTable() {
        return ledgerTable;
    }

    public void setLedgerTable(String ledgerTable) {
        this.ledgerTable = ledgerTable;
    }

    public int getLockTimeoutSeconds() {
        return lockTimeoutSeconds;
    }

    public void setLockTimeoutSeconds(int lockTimeoutSeconds) {
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
}
