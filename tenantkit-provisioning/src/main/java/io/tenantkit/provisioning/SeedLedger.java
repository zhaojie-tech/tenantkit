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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.tenantkit.core.TenantId;

/**
 * The per-tenant seed ledger: which script (by name) was applied to which
 * tenant with which checksum (ADR-0001 layer 2). The
 * {@code UNIQUE KEY (tenant_id, script_name)} is the concurrency backstop that
 * turns a lost insert race into an idempotent success: the loser observes
 * {@link DuplicateKeyException} and reports
 * {@link ProvisionReport#duplicateKeyShortCircuit()} instead of failing.
 *
 * <p>The ledger table is a <strong>global</strong> table (all tenants live in
 * it) &mdash; any future row-level filter must exempt it (ADR-0002). The
 * migration that creates it belongs to the application's own Flyway/Liquibase;
 * TenantKit does not embed a migration engine. Reference DDL (adjust types to
 * your schema conventions):
 *
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS tenantkit_seed_history (
 *   id          BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   tenant_id   VARCHAR(32)  NOT NULL,
 *   script_name VARCHAR(255) NOT NULL,
 *   checksum    CHAR(64)     NOT NULL,
 *   executed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *   UNIQUE KEY uk_tenantkit_seed (tenant_id, script_name)
 * );
 * }</pre>
 *
 * <p>On startup the auto-configuration probes the table once
 * ({@link #tableExists()}, a single {@code SELECT 1}) and logs a WARN with the
 * DDL above when it is missing; calling {@code provision} on a missing ledger
 * fails with TK-2004 and the same guidance.
 */
public class SeedLedger {

    /**
     * Reference DDL embedded in TK-2004 messages and the startup WARN.
     *
     * <p><strong>MySQL dialect</strong> ({@code ENGINE=InnoDB}, {@code UNIQUE KEY}):
     * adapt the syntax for H2/PostgreSQL (e.g. {@code CONSTRAINT ... UNIQUE (...)},
     * no engine clause). The unique constraint on
     * {@code (tenant_id, script_name)} is load-bearing — it is what makes
     * concurrent provisioning idempotent (ADR-0001).
     */
    public static final String REFERENCE_DDL = "CREATE TABLE IF NOT EXISTS tenantkit_seed_history ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "tenant_id VARCHAR(32) NOT NULL, "
            + "script_name VARCHAR(255) NOT NULL, "
            + "checksum CHAR(64) NOT NULL, "
            + "executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "UNIQUE KEY uk_tenantkit_seed (tenant_id, script_name))";

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public SeedLedger(JdbcTemplate jdbcTemplate, String tableName) {
        if (!tableName.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException(
                    "ledger table name must be a plain SQL identifier, got: " + tableName);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    /** Checksum recorded for the script, if the tenant already has it. */
    public Optional<String> findChecksum(TenantId tenant, String scriptName) {
        List<String> found = jdbcTemplate.query(
                "SELECT checksum FROM " + tableName + " WHERE tenant_id = ? AND script_name = ?",
                (rs, rowNum) -> rs.getString(1), tenant.value(), scriptName);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Records a script as applied. A {@link DuplicateKeyException} here means
     * another instance recorded the same (tenant, script) while this run was
     * executing &mdash; the caller treats it as idempotent success.
     */
    public void record(TenantId tenant, String scriptName, String checksum) {
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (tenant_id, script_name, checksum, executed_at) VALUES (?, ?, ?, ?)",
                tenant.value(), scriptName, checksum, Timestamp.from(Instant.now()));
    }

    /** Explicit repair path: overwrites the checksum for an existing record. */
    public void replaceChecksum(TenantId tenant, String scriptName, String newChecksum) {
        jdbcTemplate.update("UPDATE " + tableName + " SET checksum = ? WHERE tenant_id = ? AND script_name = ?",
                newChecksum, tenant.value(), scriptName);
    }

    /**
     * Single {@code SELECT 1} probe; {@code false} when the table is missing.
     * Uses {@code queryForList} so an empty-but-existing table still counts as
     * present ({@code queryForObject} would throw on zero rows).
     */
    public boolean tableExists() {
        try {
            jdbcTemplate.queryForList("SELECT 1 FROM " + tableName + " LIMIT 1");
            return true;
        }
        catch (DataAccessException missing) {
            return false;
        }
    }
}
