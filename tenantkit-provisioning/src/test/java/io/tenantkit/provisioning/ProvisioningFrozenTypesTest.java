package io.tenantkit.provisioning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Frozen signatures of DESIGN &sect;6.5 (SeedProfile / ProvisionOptions /
 * ProvisionReport / ScriptResult / ScriptFailure) &mdash; component access,
 * null-vs-configuration semantics and defensive copying.
 */
class ProvisioningFrozenTypesTest {

    /** &sect;6.5: `SeedProfile(String name)` maps to seed-location/&lt;name&gt;/. */
    @Test
    void seedProfileCarriesName() {
        SeedProfile profile = new SeedProfile("basic");
        assertThat(profile.name()).isEqualTo("basic");
        assertThat(SeedProfile.of("erp")).isEqualTo(new SeedProfile("erp"));
    }

    /** &sect;6.5: null fields mean "use the configured default". */
    @Test
    void provisionOptionsNullMeansConfigurationDefault() {
        ProvisionOptions options = new ProvisionOptions(null, null);
        assertThat(options.dryRun()).isNull();
        assertThat(options.lockTimeout()).isNull();
        assertThat(ProvisionOptions.defaults().dryRun()).isNull();
        assertThat(ProvisionOptions.auditOnly().dryRun()).isTrue();
        ProvisionOptions explicit = new ProvisionOptions(Boolean.FALSE, Duration.ofSeconds(7));
        assertThat(explicit.dryRun()).isFalse();
        assertThat(explicit.lockTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    /** &sect;6.5: report components frozen; null lists normalize to empty. */
    @Test
    void provisionReportNormalizesAndCopies() {
        TenantId tenant = TenantId.of("alpha");
        List<ScriptResult> executed = new ArrayList<>();
        executed.add(new ScriptResult("V1__a.sql", "aa"));
        ProvisionReport report = new ProvisionReport(tenant, executed, null, null, false);
        assertThat(report.tenant()).isSameAs(tenant);
        assertThat(report.executed()).containsExactly(new ScriptResult("V1__a.sql", "aa"));
        assertThat(report.skipped()).isEmpty();
        assertThat(report.failed()).isEmpty();
        assertThat(report.duplicateKeyShortCircuit()).isFalse();
        assertThat(report.succeeded()).isTrue();

        executed.clear();
        assertThat(report.executed()).as("report must not alias the caller's list").hasSize(1);

        ScriptFailure failure = new ScriptFailure("V2__b.sql", "java.sql.SQLException: boom");
        ProvisionReport failedReport = new ProvisionReport(tenant, List.of(), List.of(), List.of(failure), true);
        assertThat(failedReport.succeeded()).isFalse();
        assertThat(failedReport.duplicateKeyShortCircuit()).isTrue();
        assertThat(failedReport.failed()).containsExactly(failure);
    }

    /** ScriptResult / ScriptFailure are plain frozen data carriers. */
    @Test
    void scriptResultAndFailureCarryComponents() {
        assertThat(new ScriptResult("V1__a.sql", "aa").checksum()).isEqualTo("aa");
        assertThat(new ScriptFailure("V1__a.sql", "reason").reason()).isEqualTo("reason");
    }

    @Test
    void negativeLockTimeoutIsRejectedAtTheTypeBoundary() {
        assertThatThrownBy(() -> new ProvisionOptions(null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockTimeout");
    }

    @Test
    void seedProfileRejectsTraversalAndGlobCharacters() {
        assertThatThrownBy(() -> new SeedProfile("../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeedProfile("a*b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeedProfile("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SeedProfile("demo_v2").name()).isEqualTo("demo_v2");
    }
}
