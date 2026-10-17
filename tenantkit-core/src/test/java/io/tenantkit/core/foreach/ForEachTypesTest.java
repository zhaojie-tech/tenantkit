package io.tenantkit.core.foreach;

import java.time.Duration;
import java.util.List;

import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForEachTypesTest {

    @Test
    void optionsDefaultsAreSerialAndForgiving() {
        ForEachOptions defaults = ForEachOptions.defaults();
        assertThat(defaults.perTenantTimeout()).isNull();
        assertThat(defaults.executor()).isNull();
        assertThat(defaults.continueOnError()).isTrue();
        assertThat(defaults.failOnOuterTransaction()).isFalse();
    }

    @Test
    void optionsRejectNegativeTimeout() {
        assertThatCode(() -> new ForEachOptions(Duration.ofMillis(-1), null, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runResultRejectsSuccessWithFailure() {
        assertThatCode(() -> new TenantRunResult(TenantId.of("a"), true, new IllegalStateException("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runResultDefaultsNullElapsedToZero() {
        TenantRunResult result = new TenantRunResult(TenantId.of("a"), true, null, null);
        assertThat(result.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    void reportIsDefensivelyCopied() {
        TenantRunResult entry = new TenantRunResult(TenantId.of("a"), true, null, Duration.ZERO);
        ForEachReport report = new ForEachReport(List.of(entry));
        assertThatThrownBy(() -> report.perTenant().add(entry))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonRuntimeFailuresAreAggregatedInTimedRuns() {
        TenantCatalogFixture catalog = new TenantCatalogFixture(List.of(TenantId.of("alpha")));
        ForEachOptions options = new ForEachOptions(Duration.ofSeconds(2), null, true, false);
        ForEachReport report = Tenants.forEach(catalog, (id, tenant) -> {
            throw new AssertionError("jvm-level failure");
        }, options);
        assertThat(report.perTenant().get(0).success()).isFalse();
        assertThat(report.perTenant().get(0).failure()).isInstanceOf(AssertionError.class);
    }

    @Test
    void ambientCatalogRejectsNullRegistration() {
        assertThatCode(() -> Tenants.useCatalog(null)).isInstanceOf(NullPointerException.class);
    }

    static final class TenantCatalogFixture implements io.tenantkit.core.TenantCatalog {
        private final List<TenantId> tenants;

        TenantCatalogFixture(List<TenantId> tenants) {
            this.tenants = tenants;
        }

        @Override
        public List<TenantId> activeTenants() {
            return tenants;
        }
    }
}
