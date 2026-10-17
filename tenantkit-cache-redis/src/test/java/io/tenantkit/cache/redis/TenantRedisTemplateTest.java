package io.tenantkit.cache.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMissingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Behavior matrix of the tenant template against a mocked delegate
 * (DESIGN &sect;6.4 write/read path spec; container-level isolation lives in
 * the slow layer). Tenants are bound through the public structured
 * runAs/callAs scopes only — no context setters exist, by design.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantRedisTemplateTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> delegate = mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    private static final TenantId ALPHA = TenantId.of("alpha");

    private TenantRedisTemplate template(TenantKitRedisProperties.NoContextPolicy policy, List<String> sharedKeys,
            boolean keyPrefix) {
        when(delegate.opsForValue()).thenReturn(valueOps);
        TenantKitRedisProperties properties = new TenantKitRedisProperties();
        properties.setNoContextPolicy(policy);
        properties.setSharedKeys(sharedKeys);
        properties.setKeyPrefix(keyPrefix);
        return new TenantRedisTemplate(delegate, properties);
    }

    private TenantRedisTemplate template() {
        return template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of(), true);
    }

    /** Runs {@code action} with the tenant bound via the public structured scope. */
    private static void scoped(TenantId tenant, Runnable action) {
        TenantContext.runAs(tenant, action);
    }

    /** Calls {@code action} with the tenant bound via the public structured scope. */
    private static <T> T scoped(TenantId tenant, Supplier<T> action) {
        try {
            return TenantContext.callAs(tenant, action::get);
        }
        catch (RuntimeException | Error ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------
    // write / read / delete / hasKey with a bound tenant
    // ------------------------------------------------------------------

    @Test
    void writePrefixesKey() {
        TenantRedisTemplate template = template();

        scoped(ALPHA, () -> template.opsForValue().set("user:1", "v"));

        verify(valueOps).set("t:alpha:user:1", "v");
    }

    @Test
    void readPrefixesKeyAndReturnsOriginalValue() {
        TenantRedisTemplate template = template();
        when(valueOps.get("t:alpha:user:1")).thenReturn("payload");

        Object result = scoped(ALPHA, () -> template.opsForValue().get("user:1"));

        assertThat(result).isEqualTo("payload");
    }

    @Test
    void deleteAndHasKeyUsePrefixedKey() {
        TenantRedisTemplate template = template();
        when(delegate.delete("t:alpha:user:1")).thenReturn(Boolean.TRUE);
        when(delegate.hasKey("t:alpha:user:1")).thenReturn(Boolean.TRUE);

        Boolean deleted = scoped(ALPHA, () -> template.delete("user:1"));
        Boolean exists = scoped(ALPHA, () -> template.hasKey("user:1"));

        assertThat(deleted).isTrue();
        assertThat(exists).isTrue();
    }

    @Test
    void deleteCollectionPrefixesEveryKey() {
        TenantRedisTemplate template = template();
        when(delegate.delete(any(Collection.class))).thenReturn(2L);

        Long deleted = scoped(ALPHA, () -> template.delete(List.of("user:1", "user:2")));

        assertThat(deleted).isEqualTo(2L);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass((Class) Collection.class);
        verify(delegate).delete(captor.capture());
        assertThat(captor.getValue()).containsExactly("t:alpha:user:1", "t:alpha:user:2");
    }

    @Test
    void sharedKeysBypassPrefixAndNeedNoTenant() {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of("app:*"),
                true);

        // STRICT with no tenant: shared keys are the one legitimate escape
        template.opsForValue().set("app:flag", "on");
        when(valueOps.get("app:flag")).thenReturn("on");
        assertThat(template.opsForValue().get("app:flag")).isEqualTo("on");
        when(delegate.delete("app:flag")).thenReturn(Boolean.TRUE);
        when(delegate.hasKey("app:flag")).thenReturn(Boolean.TRUE);
        template.delete("app:flag");
        template.hasKey("app:flag");

        verify(valueOps).set("app:flag", "on");
        verify(delegate).delete("app:flag");
        verify(delegate).hasKey("app:flag");
    }

    @Test
    void sharedKeysAreNotPrefixedEvenWithTenant() {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of("app:*"),
                true);

        scoped(ALPHA, () -> template.opsForValue().set("app:flag", "on"));

        verify(valueOps).set("app:flag", "on");
    }

    // ------------------------------------------------------------------
    // keys / scan: paired rewrite + strip
    // ------------------------------------------------------------------

    @Test
    void keysRewritesPatternAndStripsResults() {
        TenantRedisTemplate template = template();
        when(delegate.keys("t:alpha:user:*"))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("t:alpha:user:1", "t:alpha:user:2")));

        Set<String> keys = scoped(ALPHA, () -> template.keys("user:*"));

        assertThat(keys).containsExactly("user:1", "user:2");
        verify(delegate).keys("t:alpha:user:*");
    }

    @Test
    void keysWithForeignKeysKeepsThemUntouched() {
        // only reachable via lenient-style shapes: a foreign prefix survives so
        // callers cannot mistake another tenant's keys for their namespace
        TenantRedisTemplate template = template();
        when(delegate.keys("t:alpha:user:*"))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("t:alpha:user:1", "t:beta:user:2")));

        assertThat(scoped(ALPHA, () -> template.keys("user:*"))).containsExactly("user:1", "t:beta:user:2");
    }

    @Test
    void keysWithSharedPatternPassesThroughUnrewrittenAndUnstripped() {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of("app:*"),
                true);

        when(delegate.keys("app:*")).thenReturn(Set.of("app:flag"));

        assertThat(template.keys("app:*")).containsExactly("app:flag");
        verify(delegate).keys("app:*");
    }

    @Test
    void scanRewritesPatternAndStripsLazily() {
        TenantRedisTemplate template = template();
        Cursor<String> raw = cursor("t:alpha:user:1", "t:alpha:user:2");
        when(delegate.scan(any(ScanOptions.class))).thenReturn(raw);

        Cursor<String> cursor = scoped(ALPHA,
                () -> template.scan(ScanOptions.scanOptions().match("user:*").count(100).build()));

        assertThat(cursor.next()).isEqualTo("user:1");
        assertThat(cursor.next()).isEqualTo("user:2");

        ArgumentCaptor<ScanOptions> captor = ArgumentCaptor.forClass(ScanOptions.class);
        verify(delegate).scan(captor.capture());
        assertThat(captor.getValue().getPattern()).isEqualTo("t:alpha:user:*");
        assertThat(captor.getValue().getCount()).isEqualTo(100);
    }

    @Test
    void scanBindsTenantAtCallTime() {
        // the cursor keeps stripping with the tenant bound at scan() time even
        // after the calling scope exits (paired handling, no late context leak)
        TenantRedisTemplate template = template();
        Cursor<String> raw = cursor("t:alpha:user:1");
        when(delegate.scan(any(ScanOptions.class))).thenReturn(raw);

        Cursor<String> bound = scoped(ALPHA, () -> template.scan(ScanOptions.scanOptions().match("user:*").build()));

        // scope already exited here; the strip rule is still alpha's
        assertThat(bound.next()).isEqualTo("user:1");
    }

    @Test
    void scanWithoutStringPatternPassesThrough() {
        TenantRedisTemplate template = template();
        ScanOptions raw = ScanOptions.NONE;
        Cursor<String> rawCursor = cursor();
        when(delegate.scan(raw)).thenReturn(rawCursor);

        assertThat(scoped(ALPHA, () -> template.scan(raw))).isSameAs(rawCursor);
    }

    @Test
    void scanWithSharedPatternPassesThrough() {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of("app:*"),
                true);
        ScanOptions options = ScanOptions.scanOptions().match("app:*").build();
        Cursor<String> rawCursor = cursor("app:flag");
        when(delegate.scan(options)).thenReturn(rawCursor);

        assertThat(template.scan(options)).isSameAs(rawCursor);
    }

    @Test
    void scanCursorDelegatesMetadata() {
        TenantRedisTemplate template = template();
        Cursor<String> raw = cursor("t:alpha:user:1");
        when(delegate.scan(any(ScanOptions.class))).thenReturn(raw);

        Cursor<String> wrapped = scoped(ALPHA, () -> template.scan(ScanOptions.scanOptions().match("user:*").build()));

        assertThat(wrapped.hasNext()).isTrue();
        assertThat(wrapped.next()).isEqualTo("user:1");
        assertThat(wrapped.hasNext()).isFalse();
        wrapped.getId();
        wrapped.getCursorId();
        wrapped.getPosition();
        wrapped.isClosed();
        wrapped.close();
    }

    // ------------------------------------------------------------------
    // no-context policy
    // ------------------------------------------------------------------

    @Test
    void strictWithoutTenantThrowsTk1001() {
        TenantRedisTemplate template = template();

        assertThatThrownBy(() -> template.opsForValue().set("user:1", "v"))
                .isInstanceOf(TenantMissingException.class)
                .hasMessageContaining("no tenant")
                .extracting(e -> ((TenantMissingException) e).errorCode())
                .isEqualTo("TK-1001");
        assertThatThrownBy(() -> template.opsForValue().get("user:1")).isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.delete("user:1")).isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.hasKey("user:1")).isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.keys("user:*")).isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(() -> template.scan(ScanOptions.scanOptions().match("user:*").build()))
                .isInstanceOf(TenantMissingException.class);
        verify(delegate, never()).keys(anyString());
    }

    @Test
    void lenientWithoutTenantPassesThroughRawAndWarns(CapturedOutput output) {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.LENIENT, List.of(), true);
        when(delegate.keys("user:*")).thenReturn(Set.of());

        template.opsForValue().set("user:1", "v");
        when(valueOps.get("user:1")).thenReturn("v");
        assertThat(template.opsForValue().get("user:1")).isEqualTo("v");
        template.keys("user:*");

        verify(valueOps).set("user:1", "v");
        verify(delegate).keys("user:*");
        assertThat(output.getAll()).contains("no tenant", "lenient");
    }

    @Test
    void lenientKeysReturnsResultsUnstripped() {
        TenantRedisTemplate template = template(TenantKitRedisProperties.NoContextPolicy.LENIENT, List.of(), true);
        when(delegate.keys("user:*")).thenReturn(Set.of("t:alpha:user:1"));

        assertThat(template.keys("user:*")).containsExactly("t:alpha:user:1");
    }

    // ------------------------------------------------------------------
    // key-prefix=false: transparent delegate
    // ------------------------------------------------------------------

    @Test
    void keyPrefixFalseDegradesToTransparentDelegate() {
        TenantRedisTemplate passthrough = template(TenantKitRedisProperties.NoContextPolicy.STRICT, List.of("app:*"),
                false);

        when(delegate.delete("user:1")).thenReturn(Boolean.TRUE);
        when(delegate.hasKey("user:1")).thenReturn(Boolean.TRUE);
        when(delegate.keys("user:*")).thenReturn(Set.of("user:1"));
        ScanOptions raw = ScanOptions.NONE;
        Cursor<String> empty = cursor();
        when(delegate.scan(raw)).thenReturn(empty);

        // no tenant, strict policy: still no exception — nothing is tenant-scoped any more
        passthrough.opsForValue().set("user:1", "v");
        when(valueOps.get("user:1")).thenReturn("v");
        assertThat(passthrough.opsForValue().get("user:1")).isEqualTo("v");
        passthrough.delete("user:1");
        passthrough.hasKey("user:1");
        assertThat(passthrough.keys("user:*")).containsExactly("user:1");
        assertThat(passthrough.scan(raw)).isNotNull();

        verify(valueOps).set("user:1", "v");
        verify(delegate).delete("user:1");
        verify(delegate).hasKey("user:1");
        verify(delegate).keys("user:*");
    }

    // ------------------------------------------------------------------
    // R4 #6 closed: every key-based ops family is now wrapped; only the
    // safety boundaries (raw callbacks / cluster-wide) still refuse
    // ------------------------------------------------------------------

    @Test
    void keyBasedOpsFamiliesAreWrappedAndRawSurfacesRefuseWithGuidance() {
        TenantRedisTemplate wrapped = template();

        // all key-based families return a proxy instance (mocked delegates behind them)
        when(delegate.opsForHash()).thenReturn(mock(org.springframework.data.redis.core.HashOperations.class));
        when(delegate.opsForList()).thenReturn(mock(org.springframework.data.redis.core.ListOperations.class));
        when(delegate.opsForSet()).thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        when(delegate.opsForZSet()).thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        when(delegate.opsForGeo()).thenReturn(mock(org.springframework.data.redis.core.GeoOperations.class));
        when(delegate.opsForHyperLogLog()).thenReturn(mock(org.springframework.data.redis.core.HyperLogLogOperations.class));
        when(delegate.opsForStream()).thenReturn(mock(org.springframework.data.redis.core.StreamOperations.class));
        assertThat(wrapped.opsForHash()).isNotNull();
        assertThat(wrapped.opsForList()).isNotNull();
        assertThat(wrapped.opsForSet()).isNotNull();
        assertThat(wrapped.opsForZSet()).isNotNull();
        assertThat(wrapped.opsForGeo()).isNotNull();
        assertThat(wrapped.opsForHyperLogLog()).isNotNull();
        assertThat(wrapped.opsForStream()).isNotNull();

        // deliberate safety boundaries still refuse, naming the reason
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(wrapped::opsForCluster)
                .withMessageContaining("by design");
        assertThatThrownBy(() -> wrapped.execute(mock(RedisCallback.class)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("by design");
        assertThatThrownBy(() -> wrapped.execute(mock(SessionCallback.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> wrapped.executePipelined(mock(SessionCallback.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> wrapped.executePipelined(mock(RedisCallback.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getDelegateExposesTheRawTemplate() {
        assertThat(template().getDelegate()).isSameAs(delegate);
        assertThat(template().opsForValue().getOperations()).isSameAs(delegate);
    }

    // ------------------------------------------------------------------
    // full delegation surface of TenantValueOperations
    // ------------------------------------------------------------------

    @Test
    void valueOperationsDelegatesEveryMethodWithEncodedKey() {
        TenantRedisTemplate template = template();

        scoped(ALPHA, () -> {
            ValueOperations<String, Object> ops = template.opsForValue();
            ops.set("k", "v");
            ops.set("k", "v", 10, TimeUnit.SECONDS);
            ops.set("k", "v", Duration.ofSeconds(10));
            ops.set("k", "v", 3L);
            ops.setGet("k", "v", 10, TimeUnit.SECONDS);
            ops.setGet("k", "v", Duration.ofSeconds(10));
            ops.setIfAbsent("k", "v");
            ops.setIfAbsent("k", "v", 10, TimeUnit.SECONDS);
            ops.setIfAbsent("k", "v", Duration.ofSeconds(10));
            ops.setIfPresent("k", "v");
            ops.setIfPresent("k", "v", 10, TimeUnit.SECONDS);
            ops.setIfPresent("k", "v", Duration.ofSeconds(10));
            ops.multiSet(Map.of("k", "v"));
            ops.multiSetIfAbsent(Map.of("k", "v"));
            ops.get("k");
            ops.getAndDelete("k");
            ops.getAndExpire("k", 10, TimeUnit.SECONDS);
            ops.getAndExpire("k", Duration.ofSeconds(10));
            ops.getAndPersist("k");
            ops.getAndSet("k", "v");
            ops.multiGet(List.of("k"));
            ops.increment("k");
            ops.increment("k", 2L);
            ops.increment("k", 2.5d);
            ops.decrement("k");
            ops.decrement("k", 2L);
            ops.append("k", "tail");
            ops.get("k", 0L, 3L);
            ops.size("k");
            ops.setBit("k", 0L, true);
            ops.getBit("k", 0L);
            ops.bitField("k", BitFieldSubCommands.create());
            return null;
        });

        String prefixed = "t:alpha:k";
        verify(valueOps).set(prefixed, "v");
        verify(valueOps).set(prefixed, "v", 10, TimeUnit.SECONDS);
        verify(valueOps).set(prefixed, "v", Duration.ofSeconds(10));
        verify(valueOps).set(prefixed, "v", 3L);
        verify(valueOps).setGet(prefixed, "v", 10, TimeUnit.SECONDS);
        verify(valueOps).setGet(prefixed, "v", Duration.ofSeconds(10));
        verify(valueOps).setIfAbsent(prefixed, "v");
        verify(valueOps).setIfAbsent(prefixed, "v", 10, TimeUnit.SECONDS);
        verify(valueOps).setIfAbsent(prefixed, "v", Duration.ofSeconds(10));
        verify(valueOps).setIfPresent(prefixed, "v");
        verify(valueOps).setIfPresent(prefixed, "v", 10, TimeUnit.SECONDS);
        verify(valueOps).setIfPresent(prefixed, "v", Duration.ofSeconds(10));
        verify(valueOps).multiSet(Map.of(prefixed, "v"));
        verify(valueOps).multiSetIfAbsent(Map.of(prefixed, "v"));
        verify(valueOps).get(prefixed);
        verify(valueOps).getAndDelete(prefixed);
        verify(valueOps).getAndExpire(prefixed, 10, TimeUnit.SECONDS);
        verify(valueOps).getAndExpire(prefixed, Duration.ofSeconds(10));
        verify(valueOps).getAndPersist(prefixed);
        verify(valueOps).getAndSet(prefixed, "v");
        verify(valueOps).multiGet(List.of(prefixed));
        verify(valueOps).increment(prefixed);
        verify(valueOps).increment(prefixed, 2L);
        verify(valueOps).increment(prefixed, 2.5d);
        verify(valueOps).decrement(prefixed);
        verify(valueOps).decrement(prefixed, 2L);
        verify(valueOps).append(prefixed, "tail");
        verify(valueOps).get(prefixed, 0L, 3L);
        verify(valueOps).size(prefixed);
        verify(valueOps).setBit(prefixed, 0L, true);
        verify(valueOps).getBit(prefixed, 0L);
        verify(valueOps).bitField(eq(prefixed), any(BitFieldSubCommands.class));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Cursor<String> cursor(String... elements) {
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class, withSettings().stubOnly());
        List<Boolean> hasNextAnswers = new ArrayList<>();
        for (int i = 0; i < elements.length; i++) {
            hasNextAnswers.add(Boolean.TRUE);
        }
        hasNextAnswers.add(Boolean.FALSE);
        when(cursor.hasNext()).thenReturn(hasNextAnswers.get(0),
                hasNextAnswers.subList(1, hasNextAnswers.size()).toArray(new Boolean[0]));
        if (elements.length > 0) {
            when(cursor.next()).thenReturn(elements[0],
                    java.util.Arrays.copyOfRange(elements, 1, elements.length));
        }
        return cursor;
    }
}
