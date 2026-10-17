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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * Default {@link TenantProvisioner}: the three-layer execution model of
 * DESIGN &sect;6.5 / ADR-0001, none of the layers optional:
 *
 * <ol>
 *   <li><strong>One dedicated physical connection holds the tenant lock for
 *   the whole run</strong> &mdash; delegated to {@link TenantLockManager}
 *   (default {@link MysqlGetLockManager}: {@code GET_LOCK} on a
 *   {@code ConnectionCallback}-borrowed connection, {@code RELEASE_LOCK} on
 *   the same connection in {@code finally});</li>
 *   <li><strong>Checksum ledger idempotency</strong> &mdash; every script is
 *   compared against the {@code tenantkit_seed_history} record before
 *   execution: match &rarr; skip, mismatch &rarr; hard refusal TK-2001 (never
 *   silently replay changed content), lost insert race &rarr; idempotent
 *   success with {@code duplicateKeyShortCircuit=true};</li>
 *   <li><strong>One script = one transaction</strong> &mdash; scripts run via
 *   {@link TransactionTemplate} with {@code PROPAGATION_REQUIRES_NEW}, so each
 *   commits independently of the caller and of the other scripts; a failing
 *   script fails the whole run while the ledger keeps already-committed
 *   scripts (no rollback, no replay).</li>
 * </ol>
 *
 * <p>Statements come from {@link SeedStatementParser#split(String)} after
 * {@code ${tenantId}} replacement (charset {@code [a-z0-9-]} keeps that
 * injection-free &mdash; see {@link SeedScript#sqlFor}); they execute through
 * the {@link JdbcTemplate}, which joins the script transaction's connection
 * via {@code DataSourceUtils}.
 */
public class DefaultTenantProvisioner implements TenantProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantProvisioner.class);

    private final SeedScriptLocator locator;
    private final SeedLedger ledger;
    private final TenantLockManager lockManager;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final Duration defaultLockTimeout;
    private final boolean defaultDryRun;

    public DefaultTenantProvisioner(SeedScriptLocator locator, SeedLedger ledger, TenantLockManager lockManager,
            TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate, Duration defaultLockTimeout,
            boolean defaultDryRun) {
        this.locator = locator;
        this.ledger = ledger;
        this.lockManager = lockManager;
        // Layer 3 hardening: a caller holding an open transaction must not drag seed scripts (and
        // their ledger inserts) into it - one script = one independently committed transaction
        // (ADR-0001). REQUIRES_NEW suspends any outer transaction for each script.
        transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultLockTimeout = defaultLockTimeout;
        this.defaultDryRun = defaultDryRun;
    }

    @Override
    public ProvisionReport provision(TenantId id, SeedProfile profile, ProvisionOptions options) {
        boolean dryRun = options != null && options.dryRun() != null ? options.dryRun() : defaultDryRun;
        Duration lockTimeout = options != null && options.lockTimeout() != null ? options.lockTimeout()
                : defaultLockTimeout;
        List<SeedScript> scripts = locator.locate(profile);
        if (dryRun) {
            return dryRunAudit(id, scripts);
        }
        requireLedgerTable(id);
        return lockManager.withTenantLock(id, lockTimeout, () -> executeScripts(id, scripts));
    }

    @Override
    public ProvisionReport repair(TenantId id, SeedProfile profile) {
        List<SeedScript> scripts = locator.locate(profile);
        requireLedgerTable(id);
        return lockManager.withTenantLock(id, defaultLockTimeout, () -> repairLedger(id, scripts));
    }

    /**
     * Audit-only: parse, replace placeholders, log what would run &mdash; no
     * SQL executes, the ledger stays untouched, and no lock is taken (nothing
     * is written, so mutual exclusion buys nothing here).
     */
    private ProvisionReport dryRunAudit(TenantId tenant, List<SeedScript> scripts) {
        for (SeedScript script : scripts) {
            List<String> statements = SeedStatementParser.split(script.sqlFor(tenant));
            log.info("[tenantkit-provisioning] dry-run tenant={} script={} checksum={} statements={}", tenant,
                    script.name(), script.checksum(), statements.size());
            for (String statement : statements) {
                log.info("[tenantkit-provisioning] dry-run SQL: {}", statement);
            }
        }
        return new ProvisionReport(tenant, List.of(), List.of(), List.of(), false);
    }

    /** The locked execution body: the ledger-compare / execute / record loop. */
    private ProvisionReport executeScripts(TenantId tenant, List<SeedScript> scripts) {
        List<ScriptResult> executed = new ArrayList<>();
        List<ScriptResult> skipped = new ArrayList<>();
        List<ScriptFailure> failed = new ArrayList<>();
        boolean duplicateKeyShortCircuit = false;
        for (SeedScript script : scripts) {
            Optional<String> recorded = ledger.findChecksum(tenant, script.name());
            if (recorded.isPresent()) {
                if (recorded.get().equals(script.checksum())) {
                    skipped.add(new ScriptResult(script.name(), script.checksum()));
                    continue;
                }
                // Hard refusal: replaying changed content would rewrite history (ADR-0001)
                throw new TenantProvisioningException("TK-2001", "checksum mismatch for tenant " + tenant
                        + " on script '" + script.name() + "': ledger=" + recorded.get() + " current="
                        + script.checksum() + ". A modified seed script is refused, never replayed silently; "
                        + "if the change is legitimate, call TenantProvisioner#repair to re-record explicitly");
            }
            try {
                transactionTemplate.executeWithoutResult(status -> runScript(tenant, script));
            }
            catch (RuntimeException scriptFailed) {
                failed.add(new ScriptFailure(script.name(), scriptFailed.toString()));
                throw new ProvisionFailedException(new ProvisionReport(tenant, List.copyOf(executed),
                        List.copyOf(skipped), List.copyOf(failed), duplicateKeyShortCircuit), scriptFailed);
            }
            try {
                ledger.record(tenant, script.name(), script.checksum());
            }
            catch (DuplicateKeyException concurrentRecord) {
                // Another instance executed and recorded it while we ran: idempotent
                // success ONLY if the recorded checksum matches ours; a divergence
                // (two versions racing) must surface now, not at the next run
                java.util.Optional<String> concurrentChecksum = ledger.findChecksum(tenant, script.name());
                if (concurrentChecksum.isPresent() && !concurrentChecksum.get().equals(script.checksum())) {
                    throw new ProvisionFailedException(new ProvisionReport(tenant, List.copyOf(executed),
                            List.copyOf(skipped), List.of(new ScriptFailure(script.name(),
                                    "checksum divergence under concurrency: recorded " + concurrentChecksum.get()
                                    + ", computed " + script.checksum())), duplicateKeyShortCircuit),
                            new TenantProvisioningException("TK-2001",
                                    "tenant '" + tenant.value() + "' script '" + script.name()
                                            + "' checksum mismatch (concurrent divergent record)"),
                            "TK-2001");
                }
                log.info("[tenantkit-provisioning] tenant={} script='{}' recorded by another instance "
                        + "(UNIQUE key short-circuit)", tenant, script.name());
                duplicateKeyShortCircuit = true;
                skipped.add(new ScriptResult(script.name(), script.checksum()));
                continue;
            }
            executed.add(new ScriptResult(script.name(), script.checksum()));
        }
        return new ProvisionReport(tenant, List.copyOf(executed), List.copyOf(skipped), List.copyOf(failed),
                duplicateKeyShortCircuit);
    }

    /** Repair body: re-record mismatched checksums, audit-diff each, run no SQL. */
    private ProvisionReport repairLedger(TenantId tenant, List<SeedScript> scripts) {
        List<ScriptResult> repaired = new ArrayList<>();
        List<ScriptResult> consistent = new ArrayList<>();
        for (SeedScript script : scripts) {
            Optional<String> recorded = ledger.findChecksum(tenant, script.name());
            if (recorded.isEmpty()) {
                log.info("[tenantkit-provisioning] repair tenant={} script='{}' has no ledger record yet; "
                        + "left for the next provision()", tenant, script.name());
                continue;
            }
            if (recorded.get().equals(script.checksum())) {
                consistent.add(new ScriptResult(script.name(), script.checksum()));
                continue;
            }
            log.warn("[tenantkit-provisioning] REPAIR tenant={} script='{}' checksum ledger={} -> current={}"
                    + " (audit diff; repair re-records without executing the script)", tenant, script.name(),
                    recorded.get(), script.checksum());
            ledger.replaceChecksum(tenant, script.name(), script.checksum());
            repaired.add(new ScriptResult(script.name(), script.checksum()));
        }
        return new ProvisionReport(tenant, List.copyOf(repaired), List.copyOf(consistent), List.of(), false);
    }

    private void runScript(TenantId tenant, SeedScript script) {
        List<String> statements = SeedStatementParser.split(script.sqlFor(tenant));
        log.info("[tenantkit-provisioning] executing script='{}' for tenant={} ({} statements)", script.name(),
                tenant, statements.size());
        for (String statement : statements) {
            jdbcTemplate.execute(statement);
        }
    }

    private void requireLedgerTable(TenantId tenant) {
        if (!ledger.tableExists()) {
            throw new TenantProvisioningException("TK-2004", "TK-2004: seed ledger table is missing; create it with your "
                    + "own migration (Flyway/Liquibase) before provisioning tenant " + tenant + ". Reference DDL: "
                    + SeedLedger.REFERENCE_DDL);
        }
    }
}
