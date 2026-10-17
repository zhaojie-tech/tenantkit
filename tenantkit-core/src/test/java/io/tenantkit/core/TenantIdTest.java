package io.tenantkit.core;

import java.util.stream.Stream;

import io.tenantkit.core.exception.TenantIdFormatException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TenantId validation table (matrix group C semantics live web-side; this is the value type contract). */
class TenantIdTest {

    @AfterEach
    void resetPattern() {
        TenantId.resetPattern();
    }

    @ParameterizedTest
    @ValueSource(strings = { "a", "alpha", "tenant-1", "0123456789", "a-b-c-d", "-",
            "abcdefghijklmnopqrstuvwxyz01234", "abcdefghijklmnopqrstuvwxyz012345" }) // 31 and 32 chars
    void acceptsDefaultCharset(String raw) {
        assertThat(TenantId.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = { "Alpha", "ALPHA", "a_b", "a.b", "a b", "a/b", "a;b", "a'b", "a\"b", "a\\b",
            "a\nb", "abcdefghijklmnopqrstuvwxyz0123456", "租户" }) // 33 chars = over the limit
    void rejectsViolations(String raw) {
        assertThatThrownBy(() -> TenantId.of(raw)).isInstanceOf(TenantIdFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "-", "tenant--", "a-b-c" })
    void dashOnlyAndDashesInsideAreValidPerDefaultCharset(String raw) {
        assertThat(TenantId.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullAndEmpty(String raw) {
        assertThatThrownBy(() -> TenantId.of(raw)).isInstanceOf(Exception.class);
    }

    @Test
    void toStringIsBareValueForLogSafety() {
        assertThat(TenantId.of("alpha").toString()).isEqualTo("alpha");
    }

    @Test
    void relaxedPatternIsConfigurable() {
        TenantId.usePattern("[a-zA-Z0-9]{1,16}");
        assertThat(TenantId.of("Alpha").value()).isEqualTo("Alpha");
        assertThatThrownBy(() -> TenantId.of("a_b")).isInstanceOf(TenantIdFormatException.class);
    }

    @Test
    void divergentNonDefaultPatternRewriteIsLoudButWorks() {
        TenantId.usePattern("[a-z]{2}");
        assertThat(TenantId.of("ab").value()).isEqualTo("ab");
        // second non-default rewrite: allowed (last-writer wins) but warns (R4 P2)
        TenantId.usePattern("[a-z]{3}");
        assertThat(TenantId.of("abc").value()).isEqualTo("abc");
    }

    @Test
    void relaxedPatternResetsBack() {
        TenantId.usePattern("[a-zA-Z0-9]{1,16}");
        TenantId.resetPattern();
        assertThat(TenantId.of("alpha").value()).isEqualTo("alpha");
        assertThatThrownBy(() -> TenantId.of("Alpha")).isInstanceOf(TenantIdFormatException.class);
    }

    @Test
    void equalityIsByValue() {
        assertThat(TenantId.of("alpha")).isEqualTo(TenantId.of("alpha"));
        assertThat(TenantId.of("alpha")).hasSameHashCodeAs(TenantId.of("alpha"));
        assertThat(Stream.of(TenantId.of("a"), TenantId.of("a")).distinct().count()).isEqualTo(1);
    }
}
