package io.tenantkit.provisioning;

import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ledger behavior on H2: round-trip record/find, the UNIQUE-key duplicate
 * surfacing as Spring's {@link DuplicateKeyException} (the idempotent
 * short-circuit trigger), repair's replaceChecksum, and the existence probe.
 */
class SeedLedgerTest {

    private final H2ProvisioningFixture fixture = H2ProvisioningFixture.withMigratedLedger();
    private final SeedLedger ledger = fixture.ledger;
    private final TenantId tenant = TenantId.of("alpha");

    @Test
    void recordsAndFindsChecksumsPerTenantAndScript() {
        assertThat(ledger.findChecksum(tenant, "V1__a.sql")).isEmpty();
        ledger.record(tenant, "V1__a.sql", "cafe01");
        ledger.record(TenantId.of("beta"), "V1__a.sql", "other");
        assertThat(ledger.findChecksum(tenant, "V1__a.sql")).contains("cafe01");
        assertThat(ledger.findChecksum(TenantId.of("beta"), "V1__a.sql")).contains("other");
        assertThat(ledger.findChecksum(tenant, "V2__b.sql")).isEmpty();
    }

    /** The UNIQUE KEY violation must reach us as Spring's DuplicateKeyException. */
    @Test
    void duplicateRecordSurfacesAsDuplicateKeyException() {
        ledger.record(tenant, "V1__a.sql", "cafe01");
        assertThatThrownBy(() -> ledger.record(tenant, "V1__a.sql", "cafe02"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void replaceChecksumOverwritesForRepair() {
        ledger.record(tenant, "V1__a.sql", "old");
        ledger.replaceChecksum(tenant, "V1__a.sql", "new");
        assertThat(ledger.findChecksum(tenant, "V1__a.sql")).contains("new");
    }

    @Test
    void tableExistsTrueEvenWhenTableIsEmpty() {
        assertThat(fixture.ledger.tableExists()).isTrue();
    }

    @Test
    void tableExistsFalseWhenTableMissing() {
        H2ProvisioningFixture bare = H2ProvisioningFixture.withoutLedgerTable();
        assertThat(bare.ledger.tableExists()).isFalse();
    }

    @Test
    void rejectsTableNameThatIsNotAPlainIdentifier() {
        assertThatCode(() -> new SeedLedger(fixture.jdbc, "ledger; DROP TABLE x")).isInstanceOf(
                IllegalArgumentException.class);
    }
}
