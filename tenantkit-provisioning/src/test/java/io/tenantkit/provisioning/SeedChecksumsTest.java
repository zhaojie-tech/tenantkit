package io.tenantkit.provisioning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Checksum rules (DESIGN &sect;6.5): SHA-256, CRLF&rarr;LF normalization, file name participates. */
class SeedChecksumsTest {

    @Test
    void returnsSha256HexOfSixtyFourChars() {
        assertThat(SeedChecksums.of("V1__a.sql", "SELECT 1;")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void crlfAndCrNormalizeToLf() {
        assertThat(SeedChecksums.normalize("a\r\nb\rc\nd")).isEqualTo("a\nb\nc\nd");
        String lf = SeedChecksums.of("V1__a.sql", "one\ntwo");
        assertThat(SeedChecksums.of("V1__a.sql", "one\r\ntwo")).isEqualTo(lf);
        assertThat(SeedChecksums.of("V1__a.sql", "one\rtwo")).isEqualTo(lf);
    }

    @Test
    void fileNameParticipatesSoRenamingMovesTheChecksum() {
        String first = SeedChecksums.of("V1__a.sql", "SELECT 1;");
        String renamed = SeedChecksums.of("V2__a.sql", "SELECT 1;");
        assertThat(renamed).isNotEqualTo(first);
    }

    @Test
    void contentChangeMovesTheChecksum() {
        assertThat(SeedChecksums.of("V1__a.sql", "SELECT 1;")).isNotEqualTo(SeedChecksums.of("V1__a.sql", "SELECT 2;"));
    }
}
