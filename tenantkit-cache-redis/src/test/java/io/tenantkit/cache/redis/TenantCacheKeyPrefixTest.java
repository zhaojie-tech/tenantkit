package io.tenantkit.cache.redis;

import java.util.concurrent.Callable;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the DESIGN &sect;6.4 sentence: the startup path (RedisCacheManager
 * preload/prewarm calls {@code compute(cacheName)} with no tenant bound) is
 * fixed-lenient &mdash; it must never throw, whatever
 * {@code tenantkit.redis.no-context-policy} says.
 */
class TenantCacheKeyPrefixTest {

    private final TenantCacheKeyPrefix prefix = new TenantCacheKeyPrefix();

    @Test
    void withoutTenantReturnsTenantLessPrefix() {
        assertThat(prefix.compute("users")).isEqualTo("users:");
    }

    @Test
    void withTenantReturnsTenantScopedPrefix() throws Exception {
        String computed = TenantContext.callAs(TenantId.of("alpha"), (Callable<String>) () -> prefix.compute("users"));
        assertThat(computed).isEqualTo("t:alpha:users:");
    }

    @Test
    void differentTenantsNeverShareCacheKeyspaces() throws Exception {
        String alpha = TenantContext.callAs(TenantId.of("alpha"), (Callable<String>) () -> prefix.compute("users"));
        String beta = TenantContext.callAs(TenantId.of("beta"), (Callable<String>) () -> prefix.compute("users"));
        assertThat(beta).isNotEqualTo(alpha);
    }

    @Test
    void computeNeverThrowsWithoutTenantEvenUnderStrictPolicy() {
        // the pinned startup-path rule: missing context is always lenient here,
        // regardless of tenantkit.redis.no-context-policy
        assertThat(prefix.compute("cache")).doesNotContain("t:");
    }

    @Test
    void implementsTheSpringDataRedisCacheKeyPrefixContract() {
        // compute returns String per spring-data-redis 4.x (javap-verified);
        // SEPARATOR is Spring Data's own "::" — our shapes are self-consistent
        assertThat(prefix).isInstanceOf(org.springframework.data.redis.cache.CacheKeyPrefix.class);
        assertThat(org.springframework.data.redis.cache.CacheKeyPrefix.SEPARATOR).isEqualTo("::");
    }
}
