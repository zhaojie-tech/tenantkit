package io.tenantkit.provisioning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.tenantkit.core.exception.TenantProvisioningException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Script location and ordering: {@code V<n>} numeric order, gap tolerance with
 * WARN, non-matching files ignored, empty profile rejected (TK-2005), and
 * {@code file:} locations for repair scenarios.
 */
@ExtendWith(OutputCaptureExtension.class)
class SeedScriptLocatorTest {

    private final SeedScriptLocator locator = new SeedScriptLocator("classpath:db/tenant-seed/");

    @Test
    void ordersNumericallyNotLexicographically() {
        var scripts = locator.locate(SeedProfile.of("ordered"));
        assertThat(scripts).extracting(SeedScript::name)
                .containsExactly("V1__first.sql", "V2__second.sql", "V10__tenth.sql");
    }

    @Test
    void computesChecksumOverNameAndContent() {
        var scripts = locator.locate(SeedProfile.of("basic"));
        for (SeedScript script : scripts) {
            assertThat(script.checksum()).hasSize(64).isEqualTo(SeedChecksums.of(script.name(), script.sql()));
        }
    }

    @Test
    void toleratesMissingSequenceNumbersWithWarn(CapturedOutput output) {
        var scripts = locator.locate(SeedProfile.of("gapped"));
        assertThat(scripts).extracting(SeedScript::name).containsExactly("V1__first.sql", "V3__third.sql");
        assertThat(output.getAll()).contains("gaps");
    }

    @Test
    void ignoresFilesThatDoNotMatchTheNamingConvention(CapturedOutput output) {
        var scripts = locator.locate(SeedProfile.of("mixed"));
        assertThat(scripts).extracting(SeedScript::name).containsExactly("V1__ok.sql");
        assertThat(output.getAll()).contains("V1.sql");
    }

    @Test
    void emptyProfileIsRefusedNotSilentlyAccepted() {
        assertThatThrownBy(() -> locator.locate(SeedProfile.of("no-such-profile")))
                .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2005");
                    assertThat(ex.getMessage()).contains("no-such-profile");
                });
    }

    /** file: locations enable the mutate-and-repair scenarios of the H2 tier. */
    @Test
    void locatesScriptsFromFileLocation(@TempDir Path tempDir) throws Exception {
        Path profileDir = Files.createDirectories(tempDir.resolve("basic"));
        Path script = profileDir.resolve("V1__insert.sql");
        Files.writeString(script, "INSERT INTO t VALUES ('${tenantId}');\r\n", StandardCharsets.UTF_8);

        // the locator itself appends the profile directory: seedLocation points at the parent
        SeedScriptLocator fileLocator = new SeedScriptLocator("file:" + tempDir + "/");
        var scripts = fileLocator.locate(SeedProfile.of("basic"));
        assertThat(scripts).hasSize(1);
        assertThat(scripts.get(0).sql()).isEqualTo("INSERT INTO t VALUES ('${tenantId}');\n");
    }

    @Test
    void mutatingFileChangesChecksumButNotName(@TempDir Path tempDir) throws Exception {
        Path profileDir = Files.createDirectories(tempDir.resolve("basic"));
        Path script = profileDir.resolve("V1__insert.sql");
        Files.writeString(script, "INSERT INTO t VALUES (1);", StandardCharsets.UTF_8);
        SeedScript before = new SeedScriptLocator("file:" + tempDir + "/")
                .locate(SeedProfile.of("basic")).get(0);

        Files.writeString(script, "INSERT INTO t VALUES (2);", StandardCharsets.UTF_8);
        SeedScript after = new SeedScriptLocator("file:" + tempDir + "/")
                .locate(SeedProfile.of("basic")).get(0);

        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.checksum()).isNotEqualTo(before.checksum());
    }
}
