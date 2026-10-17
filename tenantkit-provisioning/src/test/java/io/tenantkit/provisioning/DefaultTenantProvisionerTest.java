package io.tenantkit.provisioning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-model behavior on H2 (fast tier): first-run execution + ledger
 * recording, second-run skip, checksum hard refusal (TK-2001), repair,
 * script-failure ledger preservation, dry-run, placeholder replacement,
 * duplicate-key short-circuit, lock timeout, and the missing-ledger guidance.
 * The MySQL {@code GET_LOCK} semantics live in the slow container tier.
 */
@ExtendWith(OutputCaptureExtension.class)
class DefaultTenantProvisionerTest {

    private final TenantId tenantA = TenantId.of("tenant-a");
    private final TenantId tenantB = TenantId.of("tenant-b");

    // ---- first run / idempotent skip -------------------------------------------------

    @Test
    void firstProvisionExecutesAllScriptsAndRecordsLedger() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();

        ProvisionReport report = fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.defaults());

        assertThat(report.succeeded()).isTrue();
        assertThat(report.duplicateKeyShortCircuit()).isFalse();
        assertThat(report.executed()).extracting(ScriptResult::scriptName)
                .containsExactly("V1__create_notes.sql", "V2__insert_notes.sql");
        assertThat(report.skipped()).isEmpty();
        assertThat(report.failed()).isEmpty();
        assertThat(fixture.ledgerRowCount(tenantA.value())).isEqualTo(2);
        Integer notes = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_notes WHERE tenant_id = ?", Integer.class, tenantA.value());
        assertThat(notes).isEqualTo(2);
    }

    /** Placeholder replaced for every statement; semicolons in literals survive. */
    @Test
    void placeholderIsReplacedAndLiteralSemicolonsSurvive() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();
        fixture.provisioner.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());

        Integer literalSemicolons = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_notes WHERE tenant_id = ? AND note = 'note; with semicolon'",
                Integer.class, tenantA.value());
        assertThat(literalSemicolons).isEqualTo(1);

        fixture.provisioner.provision(tenantB, SeedProfile.of("basic"), ProvisionOptions.defaults());
        Integer tenantBRows = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_notes WHERE tenant_id = ?", Integer.class, tenantB.value());
        assertThat(tenantBRows).isEqualTo(2);
        Integer tenantARows = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_notes WHERE tenant_id = ?", Integer.class, tenantA.value());
        assertThat(tenantARows).isEqualTo(2);
    }

    @Test
    void secondProvisionSkipsEverythingAndReplaysNothing() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();
        fixture.provisioner.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());
        Integer rowsBefore = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM tenant_notes", Integer.class);

        ProvisionReport second = fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.defaults());

        assertThat(second.executed()).isEmpty();
        assertThat(second.skipped()).extracting(ScriptResult::scriptName)
                .containsExactly("V1__create_notes.sql", "V2__insert_notes.sql");
        Integer rowsAfter = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM tenant_notes", Integer.class);
        assertThat(rowsAfter).isEqualTo(rowsBefore);
    }

    // ---- checksum refusal + repair ---------------------------------------------------

    @Test
    void changedScriptChecksumIsHardRefusedAndLedgerKept(@TempDir Path tempDir) throws Exception {
        H2ProvisioningFixture fixture = fileBasedFixture(tempDir);
        fixture.provisioner.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());
        rewriteScript(tempDir, "INSERT INTO tenant_notes (tenant_id, note) VALUES ('${tenantId}', 'revised');");

        assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.defaults()))
                .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2001");
                    assertThat(ex.getMessage()).contains("checksum");
                });
        assertThat(fixture.ledgerRowCount(tenantA.value())).as("ledger untouched by the refusal").isEqualTo(1);
    }

    @Test
    void repairReRecordsChecksumWithAuditDiffAndUnblocksProvision(@TempDir Path tempDir) throws Exception {
        H2ProvisioningFixture fixture = fileBasedFixture(tempDir);
        fixture.provisioner.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());
        String oldChecksum = fixture.ledger.findChecksum(tenantA, "V1__insert.sql").orElseThrow();
        rewriteScript(tempDir, "INSERT INTO tenant_notes (tenant_id, note) VALUES ('${tenantId}', 'revised');");
        String newChecksum = fixture.locator.locate(SeedProfile.of("basic")).get(0).checksum();

        ProvisionReport repaired = fixture.provisioner.repair(tenantA, SeedProfile.of("basic"));

        assertThat(repaired.failed()).isEmpty();
        assertThat(repaired.executed()).extracting(ScriptResult::scriptName).containsExactly("V1__insert.sql");
        assertThat(fixture.ledger.findChecksum(tenantA, "V1__insert.sql")).contains(newChecksum);

        ProvisionReport afterRepair = fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.defaults());
        assertThat(afterRepair.succeeded()).isTrue();
        assertThat(afterRepair.skipped()).extracting(ScriptResult::scriptName).containsExactly("V1__insert.sql");
    }

    private H2ProvisioningFixture fileBasedFixture(Path tempDir) throws Exception {
        Path profileDir = Files.createDirectories(tempDir.resolve("basic"));
        Files.writeString(profileDir.resolve("V1__insert.sql"),
                "CREATE TABLE IF NOT EXISTS tenant_notes (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                        + "tenant_id VARCHAR(32) NOT NULL, note VARCHAR(64) NOT NULL);\n"
                        + "INSERT INTO tenant_notes (tenant_id, note) VALUES ('${tenantId}', 'seed-note');",
                StandardCharsets.UTF_8);
        return new H2ProvisioningFixture.Builder()
                .seedLocation("file:" + tempDir + "/")
                .build()
                .migrated();
    }

    private void rewriteScript(Path tempDir, String content) throws Exception {
        Files.writeString(tempDir.resolve("basic").resolve("V1__insert.sql"), content, StandardCharsets.UTF_8);
    }

    // ---- script failure ---------------------------------------------------------------

    @Test
    void scriptFailureFailsWholeRunKeepsLedgerAndReportsDetails(@TempDir Path tempDir) throws Exception {
        Path profileDir = Files.createDirectories(tempDir.resolve("failing"));
        Files.writeString(profileDir.resolve("V1__create.sql"),
                "CREATE TABLE tenant_kv (tenant_id VARCHAR(32) NOT NULL, k VARCHAR(16) NOT NULL);",
                StandardCharsets.UTF_8);
        Files.writeString(profileDir.resolve("V2__broken.sql"),
                "INSERT INTO table_that_does_not_exist VALUES (1);", StandardCharsets.UTF_8);
        H2ProvisioningFixture fixture = new H2ProvisioningFixture.Builder().seedLocation("file:" + tempDir + "/")
                .build().migrated();

        assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("failing"),
                ProvisionOptions.defaults())).isInstanceOfSatisfying(ProvisionFailedException.class, ex -> {
                    ProvisionReport report = ex.report();
                    assertThat(report.executed()).extracting(ScriptResult::scriptName)
                            .containsExactly("V1__create.sql");
                    assertThat(report.failed()).hasSize(1);
                    assertThat(report.failed().get(0).scriptName()).isEqualTo("V2__broken.sql");
                    assertThat(report.failed().get(0).reason()).contains("table_that_does_not_exist");
                });
        assertThat(fixture.ledgerRowCount(tenantA.value())).isEqualTo(1);

        // retry: V1 stays recorded (no replay), the run fails again at V2
        assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("failing"),
                ProvisionOptions.defaults())).isInstanceOf(ProvisionFailedException.class)
                .satisfies(e -> assertThat(((ProvisionFailedException) e).report().skipped())
                        .extracting(ScriptResult::scriptName).containsExactly("V1__create.sql"));
    }

    // ---- dry-run -----------------------------------------------------------------------

    @Test
    void dryRunParsesReplacesAndAuditsButNeverExecutesOrRecords(CapturedOutput output) {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withoutLedgerTable();

        ProvisionReport report = fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.auditOnly());

        assertThat(report.executed()).isEmpty();
        assertThat(report.skipped()).isEmpty();
        assertThat(report.failed()).isEmpty();
        assertThat(output.getAll()).contains("dry-run").contains("V2__insert_notes.sql")
                .contains("'tenant-a'").contains("seed-note");
        Integer notesTables = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'TENANT_NOTES'", Integer.class);
        assertThat(notesTables).isZero();
    }

    @Test
    void configuredDryRunIsUsedAndPerCallOptionOverridesIt() {
        H2ProvisioningFixture dryByDefault = new H2ProvisioningFixture.Builder().defaultDryRun(true).build()
                .migrated();
        ProvisionReport dry = dryByDefault.provisioner.provision(tenantA, SeedProfile.of("basic"), null);
        assertThat(dry.executed()).isEmpty();
        assertThat(dryByDefault.ledgerRowCount(tenantA.value())).isZero();

        ProvisionReport executed = dryByDefault.provisioner.provision(tenantA, SeedProfile.of("basic"),
                new ProvisionOptions(Boolean.FALSE, null));
        assertThat(executed.executed()).hasSize(2);
        assertThat(dryByDefault.ledgerRowCount(tenantA.value())).isEqualTo(2);
    }

    // ---- lock timeout + options override ----------------------------------------------

    @Test
    void lockTimeoutFailsWithTk2002AndOptionsOverrideConfiguredTimeout() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> fixture.lockManager.holdWhile(tenantA, Duration.ofSeconds(30), () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "held";
        }));
        holder.start();
        try {
            // wait until the holder actually owns the permit
            while (!fixture.lockManager.holdsPermit(tenantA) && holder.isAlive()) {
                Thread.sleep(10);
            }
            assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                    new ProvisionOptions(null, Duration.ofMillis(120))))
                    .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("TK-2002");
                        assertThat(ex.getMessage()).contains("lock");
                    });
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        finally {
            release.countDown();
            try {
                holder.join(5000);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void configuredLockTimeoutIsUsedWhenOptionsAreDefaults() {
        // options stay null, so the configured default (200ms here) must drive the wait
        H2ProvisioningFixture fixture = new H2ProvisioningFixture.Builder()
                .defaultLockTimeout(Duration.ofMillis(200)).build().migrated();
        fixture.lockManager.holdWhile(tenantA, Duration.ofSeconds(30), () -> {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                    ProvisionOptions.defaults()))
                    .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("TK-2002");
                        assertThat(ex.getMessage()).contains("lock");
                    });
            assertThat(System.nanoTime() - start).as("the configured default timeout drove the wait")
                    .isGreaterThanOrEqualTo(100_000_000L);
            return "held";
        });
    }

    // ---- duplicate-key short circuit ---------------------------------------------------

    @Test
    void concurrentLedgerInsertShortCircuitsAsIdempotentSuccess() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();
        SeedLedger racingLedger = new SeedLedger(fixture.jdbc, "tenantkit_seed_history") {
            @Override
            public void record(TenantId tenant, String scriptName, String checksum) {
                // simulate the other instance winning the insert race
                fixture.insertLedgerRow(tenant, scriptName, checksum);
                throw new DuplicateKeyException("simulated concurrent UNIQUE KEY hit");
            }
        };
        DefaultTenantProvisioner racing = new BuilderReplacement(fixture, racingLedger).build();

        ProvisionReport report = racing.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());

        assertThat(report.succeeded()).isTrue();
        assertThat(report.duplicateKeyShortCircuit()).isTrue();
        assertThat(report.executed()).isEmpty();
        assertThat(report.skipped()).extracting(ScriptResult::scriptName)
                .containsExactly("V1__create_notes.sql", "V2__insert_notes.sql");
        assertThat(fixture.ledgerRowCount(tenantA.value())).isEqualTo(2);
    }

    /** Rebuilds a provisioner around a different ledger, mirroring the fixture wiring. */
    private static final class BuilderReplacement {

        private final H2ProvisioningFixture fixture;
        private final SeedLedger ledger;

        BuilderReplacement(H2ProvisioningFixture fixture, SeedLedger ledger) {
            this.fixture = fixture;
            this.ledger = ledger;
        }

        DefaultTenantProvisioner build() {
            return new DefaultTenantProvisioner(fixture.locator, ledger, fixture.lockManager,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                    fixture.jdbc.getDataSource())),
                    fixture.jdbc, Duration.ofSeconds(30), false);
        }
    }

    // ---- missing ledger ----------------------------------------------------------------

    @Test
    void missingLedgerTableFailsWithGuidanceAndStartupWarn(CapturedOutput output) {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withoutLedgerTable();
        assertThat(fixture.ledger.tableExists()).isFalse();

        assertThatThrownBy(() -> fixture.provisioner.provision(tenantA, SeedProfile.of("basic"),
                ProvisionOptions.defaults()))
                .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2004");
                    assertThat(ex.getMessage()).contains("CREATE TABLE").contains("tenantkit_seed_history");
                });
    }

    @Test
    void repairAlsoRequiresTheLedgerTable() {
        H2ProvisioningFixture fixture = H2ProvisioningFixture.withoutLedgerTable();
        assertThatThrownBy(() -> fixture.provisioner.repair(tenantA, SeedProfile.of("basic")))
                .isInstanceOfSatisfying(TenantProvisioningException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("TK-2004"));
    }

    // ---- repair edge cases -------------------------------------------------------------

    @Test
    void repairSkipsUnrecordedAndConsistentScripts(@TempDir Path tempDir) throws Exception {
        H2ProvisioningFixture fixture = fileBasedFixture(tempDir);
        fixture.provisioner.provision(tenantA, SeedProfile.of("basic"), ProvisionOptions.defaults());

        ProvisionReport report = fixture.provisioner.repair(tenantA, SeedProfile.of("basic"));

        assertThat(report.executed()).as("nothing to repair").isEmpty();
        assertThat(report.skipped()).extracting(ScriptResult::scriptName).containsExactly("V1__insert.sql");
    }
}
