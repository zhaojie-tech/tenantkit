package io.tenantkit.cache.redis;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.test.TenantSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-level isolation proof (M2 acceptance item "Redis isolation",
 * DESIGN &sect;8 integration layer). Requires Docker: run with
 * {@code mvn verify -Pcontainers}; the default (fast) build excludes this
 * class via the {@code slow} JUnit tag.
 */
@Tag("slow")
class TenantRedisIsolationTest {

    private static final TenantId ALPHA = TenantId.of("alpha");
    private static final TenantId BETA = TenantId.of("beta");

    // R4 P2: ONE Lettuce factory for the whole class, destroyed at the end —
    // the previous per-test construction leaked a netty client + connection per case
    private static LettuceConnectionFactory factory;
    private static TenantRedisTemplate sharedTemplate;

    private static synchronized TenantRedisTemplate template() {
        if (sharedTemplate == null) {
            TenantSupport.redis();
            factory = new LettuceConnectionFactory(
                    container().getHost(), container().getMappedPort(6379));
            factory.afterPropertiesSet();
            RedisTemplate<String, Object> raw = new RedisTemplate<>();
            raw.setConnectionFactory(factory);
            raw.setKeySerializer(StringRedisSerializer.UTF_8);
            raw.setHashKeySerializer(StringRedisSerializer.UTF_8);
            raw.setValueSerializer(new JdkSerializationRedisSerializer());
            raw.afterPropertiesSet();
            TenantKitRedisProperties properties = new TenantKitRedisProperties();
            properties.setSharedKeys(List.of("app:*"));
            sharedTemplate = new TenantRedisTemplate(raw, properties);
        }
        return sharedTemplate;
    }

    @org.junit.jupiter.api.AfterAll
    static void destroyFactory() {
        if (factory != null) {
            try {
                factory.destroy();
            }
            catch (Exception ignored) {
                // best-effort teardown; the container itself is Ryuk's job
            }
            factory = null;
            sharedTemplate = null;
        }
    }

    private static org.testcontainers.containers.GenericContainer<?> container() {
        return TenantSupport.redis();
    }

    /** alpha's write is invisible to beta and vice versa — same logical key, two physical keys. */
    @Test
    void writesAreIsolatedPerTenant() {
        TenantRedisTemplate template = template();

        TenantContext.runAs(ALPHA, () -> template.opsForValue().set("user:1", "alpha-value"));
        TenantContext.runAs(BETA, () -> assertThat(template.opsForValue().get("user:1")).isNull());

        TenantContext.runAs(BETA, () -> template.opsForValue().set("user:1", "beta-value"));

        TenantContext.runAs(ALPHA, () -> assertThat(template.opsForValue().get("user:1")).isEqualTo("alpha-value"));
        TenantContext.runAs(BETA, () -> assertThat(template.opsForValue().get("user:1")).isEqualTo("beta-value"));
    }

    /** The shared-key allow-list is the designed window across tenants. */
    @Test
    void sharedKeysAreVisibleToBothTenants() {
        TenantRedisTemplate template = template();

        TenantContext.runAs(ALPHA, () -> template.opsForValue().set("app:flag", "global"));

        TenantContext.runAs(ALPHA, () -> assertThat(template.opsForValue().get("app:flag")).isEqualTo("global"));
        TenantContext.runAs(BETA, () -> assertThat(template.opsForValue().get("app:flag")).isEqualTo("global"));
    }

    /** keys() returns only the calling tenant's keys, stripped (paired rewrite + strip against real Redis). */
    @Test
    void keysReturnsOnlyOwnTenantKeysStripped() throws Exception {
        TenantRedisTemplate template = template();

        String suffix = Long.toHexString(System.nanoTime());
        String alphaKey = "iso:" + suffix + ":a";
        String betaKey = "iso:" + suffix + ":b";
        TenantContext.runAs(ALPHA, () -> template.opsForValue().set(alphaKey, "a"));
        TenantContext.runAs(BETA, () -> template.opsForValue().set(betaKey, "b"));

        Set<String> alphaKeys = TenantContext.callAs(ALPHA, () -> template.keys("iso:" + suffix + ":*"));
        Set<String> betaKeys = TenantContext.callAs(BETA, () -> template.keys("iso:" + suffix + ":*"));

        assertThat(alphaKeys).containsExactly(alphaKey);
        assertThat(betaKeys).containsExactly(betaKey);
    }

    /** scan() agrees with keys() on the stripped face against the real server. */
    @Test
    void scanMatchesKeysSemantics() throws Exception {
        TenantRedisTemplate template = template();

        String suffix = Long.toHexString(System.nanoTime());
        String key = "scn:" + suffix;
        TenantContext.runAs(ALPHA, () -> template.opsForValue().set(key, "a"));

        String found = TenantContext.callAs(ALPHA, () -> {
            try (Cursor<String> cursor = template.scan(ScanOptions.scanOptions().match("scn:" + suffix).build())) {
                // never next() blindly: a SCAN batch can legitimately come back empty first
                while (cursor.hasNext()) {
                    return cursor.next();
                }
                return null;
            }
        });
        assertThat(found).isEqualTo(key);
    }

    /** Physical keys on the wire carry the t:<tenant>: prefix — the serializer-view guarantee. */
    @Test
    void physicalKeysCarryTheTenantPrefix() {
        TenantRedisTemplate template = template();

        TenantContext.runAs(ALPHA, () -> template.opsForValue().set("phys:k", "v", Duration.ofSeconds(60)));

        Object raw = template.getDelegate().opsForValue().get("t:alpha:phys:k");
        assertThat(raw).isEqualTo("v");
    }
}
