package io.tenantkit.cache.redis;

import java.util.List;

import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit matrix for the unique strip layer (DESIGN &sect;6.4 R5): prefix rule,
 * strip rule, shared-key glob exemption and round-trip identity.
 */
class TenantKeyCodecTest {

    private static final TenantId ALPHA = TenantId.of("alpha");
    private static final TenantId BETA = TenantId.of("beta");

    private TenantKeyCodec codec(List<String> sharedKeys) {
        return new TenantKeyCodec(true, sharedKeys);
    }

    @Test
    void encodeAddsTenantPrefix() {
        assertThat(codec(List.of()).encode("user:1", ALPHA)).isEqualTo("t:alpha:user:1");
    }

    @Test
    void decodeStripsOwnPrefixOnly() {
        TenantKeyCodec c = codec(List.of());
        assertThat(c.decode("t:alpha:user:1", ALPHA)).isEqualTo("user:1");
    }

    @Test
    void decodeKeepsForeignPrefix() {
        // foreign-tenant keys must never appear stripped: no cross-tenant illusions
        assertThat(codec(List.of()).decode("t:beta:user:1", ALPHA)).isEqualTo("t:beta:user:1");
    }

    @Test
    void sharedKeysAreExemptInBothDirections() {
        TenantKeyCodec c = codec(List.of("app:*", "config"));
        assertThat(c.isShared("app:feature-flag")).isTrue();
        assertThat(c.isShared("config")).isTrue();
        assertThat(c.isShared("appx:other")).isFalse();

        assertThat(c.encode("app:feature-flag", ALPHA)).isEqualTo("app:feature-flag");
        assertThat(c.decode("app:feature-flag", ALPHA)).isEqualTo("app:feature-flag");
        assertThat(c.encode("config", null)).isEqualTo("config");
    }

    @Test
    void sharedKeyGlobMatchesNestedSegments() {
        // Ant patterns over the full key string: 'app:*' covers everything under the namespace
        TenantKeyCodec c = codec(List.of("app:*"));
        assertThat(c.isShared("app:config:sub:deep")).isTrue();
        assertThat(c.isShared("user:1")).isFalse();
    }

    @Test
    void nullTenantMapsUnchanged() {
        TenantKeyCodec c = codec(List.of());
        assertThat(c.encode("user:1", null)).isEqualTo("user:1");
        assertThat(c.decode("user:1", null)).isEqualTo("user:1");
    }

    @Test
    void roundTripIsIdentityForRegularKeys() {
        TenantKeyCodec c = codec(List.of("app:*"));
        for (String key : List.of("user:1", "session:abc", "app:flag", "counter")) {
            for (TenantId tenant : List.of(ALPHA, BETA)) {
                assertThat(c.decode(c.encode(key, tenant), tenant))
                        .as("round trip of %s under %s", key, tenant)
                        .isEqualTo(key);
            }
        }
    }

    @Test
    void differentTenantsYieldDifferentPhysicalKeys() {
        TenantKeyCodec c = codec(List.of());
        assertThat(c.encode("user:1", ALPHA)).isNotEqualTo(c.encode("user:1", BETA));
    }

    @Test
    void keyPrefixFalseIsIdentityEverywhere() {
        TenantKeyCodec c = new TenantKeyCodec(false, List.of("app:*"));
        assertThat(c.isKeyPrefix()).isFalse();
        assertThat(c.isShared("app:anything")).isFalse();
        assertThat(c.encode("user:1", ALPHA)).isEqualTo("user:1");
        assertThat(c.decode("t:alpha:user:1", ALPHA)).isEqualTo("t:alpha:user:1");
    }

    @Test
    void nullSharedKeyListBehavesAsEmpty() {
        TenantKeyCodec c = new TenantKeyCodec(true, null);
        assertThat(c.sharedKeys()).isEmpty();
        assertThat(c.encode("user:1", ALPHA)).isEqualTo("t:alpha:user:1");
    }

    @Test
    void tenantPrefixHasDocumentedShape() {
        assertThat(codec(List.of()).tenantPrefix(ALPHA)).isEqualTo("t:alpha:");
    }

    @Test
    void rejectsNullKeyAndNullTenantPrefix() {
        TenantKeyCodec c = codec(List.of());
        assertThatNullPointerException().isThrownBy(() -> c.encode(null, ALPHA));
        assertThatNullPointerException().isThrownBy(() -> c.decode(null, ALPHA));
        assertThatNullPointerException().isThrownBy(() -> c.tenantPrefix(null));
    }
}
