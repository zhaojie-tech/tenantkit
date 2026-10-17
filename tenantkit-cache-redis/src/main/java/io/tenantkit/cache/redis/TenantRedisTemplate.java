/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.cache.redis;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMissingException;

/**
 * Tenant-aware facade over a {@link RedisTemplate}&lt;String, Object&gt;
 * (DESIGN &sect;6.4). It deliberately <em>composes</em> the delegate instead
 * of extending it &mdash; overriding a class with ~90 entry points invites
 * exactly the missed-method holes this kit exists to close &mdash; and
 * exposes a consciously small, tenant-safe surface:
 *
 * <ul>
 * <li>{@link #opsForValue()} &mdash; full {@link ValueOperations} face,
 * every key encoded on the way in ({@code t:<tenant>:} prefix unless the key
 * matches the {@code sharedKeys} allow-list);</li>
 * <li>{@link #keys(String)} / {@link #scan(ScanOptions)} &mdash; the pattern
 * is rewritten with the same rule and the returned key set is stripped with
 * the matching inverse rule (paired handling); shared-key patterns are passed
 * through unrewritten and unstipped;</li>
 * <li>{@link #delete(String)}, {@link #delete(Collection)},
 * {@link #hasKey(String)} &mdash; tenant-prefixed single-key access;</li>
 * <li>{@link #opsForHash()}, {@link #opsForList()}, {@link #opsForSet()},
 * {@link #opsForZSet()}, {@link #opsForGeo()}, {@link #opsForHyperLogLog()},
 * {@link #opsForStream()} &mdash; every key-taking method of every ops family
 * is rewritten through a delegating proxy. Only <em>declared key positions</em>
 * are touched (first key argument, the multi-key methods
 * {@code SetOperations.move}, the {@code *AndStore} family,
 * {@code ListOperations.rightPopAndLeftPush},
 * {@code HyperLogLogOperations.union}, and the stream read/range/record
 * overloads), never values or fields. Stream reads re-key returned records
 * back to logical form;</li>
 * <li>{@code bound*Ops(key)} &mdash; the key is encoded once at binding time
 * and the returned bound ops object is the native one;</li>
 * <li>{@link #opsForCluster()}, {@link #execute(RedisCallback)},
 * {@link #execute(SessionCallback)}, {@link #executePipelined(SessionCallback)},
 * {@link #executePipelined(RedisCallback)} throw
 * {@link UnsupportedOperationException} &mdash; deliberate safety boundaries,
 * not feature gaps: raw callbacks and cluster-wide commands cannot be
 * key-rewritten, and claiming otherwise would be a lie about isolation.</li>
 * </ul>
 *
 * <p><strong>Key-name direction semantics (DESIGN &sect;6.4 R1-P1/R5).</strong>
 * In the delegate's serializer view, keys and {@code keys()}/{@code scan()}
 * results carry the {@code t:<tenant>:} prefix; this wrapper presents them in
 * the stripped, logical form. The strip layer is unique and lives in the ops
 * wrapper (never in a serializer). The {@code keys()} pattern travels through
 * the delegate's key serializer exactly as the raw template would (verified
 * against Spring Data Redis 4.0.x: {@code keys(K)} serializes the pattern,
 * {@code scan(ScanOptions)} passes its string match pattern to the connection
 * unserialized); this wrapper rewrites at the string level before either
 * path, so serializer behavior is unchanged.
 *
 * <p><strong>KEYS in production.</strong> {@code KEYS} is O(N) over the whole
 * keyspace and blocks Redis; prefer {@link #scan(ScanOptions)} in application
 * code and reserve {@link #keys(String)} for tooling/maintenance.
 *
 * <p><strong>No-context policy.</strong> With
 * {@code tenantkit.redis.no-context-policy=strict} (the default) any
 * non-shared key access without a bound tenant throws
 * {@link TenantMissingException} (TK-1001) &mdash; the same fail-fast
 * philosophy as resolution itself; silently hitting an unprefixed key from a
 * scheduled job is the write-side twin of silently defaulting a tenant.
 * {@code lenient} passes the key through unprefixed and logs a warning.
 * Shared-key access never needs a tenant. With
 * {@code tenantkit.redis.key-prefix=false} the whole layer degrades to a
 * transparent delegate: no rewriting, no stripping, no policy.
 *
 * <p><strong>Opt-in, not @Primary.</strong> The auto-configuration registers
 * this bean without {@code @Primary}: injecting the raw {@code RedisTemplate}
 * keeps bypassing tenant rewriting by design, which is how shared
 * infrastructure keys are handled when the allow-list does not fit.
 */
public class TenantRedisTemplate {

    private static final Logger LOG = LoggerFactory.getLogger(TenantRedisTemplate.class);

    private final RedisTemplate<String, Object> delegate;
    private final TenantKitRedisProperties properties;
    private final TenantKeyCodec codec;

    /**
     * @param delegate   the container's native {@code RedisTemplate<String, Object>} (or an
     *                   adapted StringRedisTemplate: keys stay Strings, but VALUES are
     *                   serialized with the String serializer in that case — storing POJOs
     *                   requires a {@code RedisTemplate<String, Object>} bean)
     * @param properties bound {@code tenantkit.redis.*} configuration
     */
    public TenantRedisTemplate(RedisTemplate<String, Object> delegate, TenantKitRedisProperties properties) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.codec = new TenantKeyCodec(properties.isKeyPrefix(), properties.getSharedKeys());
    }

    /**
     * Tenant-aware value operations; see the class javadoc for key semantics.
     * Proxied (like every other family) instead of hand-implemented: Spring Data
     * Redis 4.1 changed the abstract method set of {@link ValueOperations}
     * (added {@code getAndExpire}/{@code compareAndSet}/{@code SetSpec} variants),
     * which a hand-written implementation cannot satisfy across a dual-version
     * matrix. The proxy adapts to whichever interface version is loaded.
     */
    public ValueOperations<String, Object> opsForValue() {
        return TenantOpsProxies.wrap(ValueOperations.class, delegate.opsForValue(),
                this::encodeKey, key -> key, delegate);
    }

    /**
     * {@code KEYS pattern} with the tenant rule applied to the pattern and the
     * inverse rule applied to every returned key (paired handling). Prefer
     * {@link #scan(ScanOptions)} in application code &mdash; {@code KEYS}
     * blocks Redis and is meant for tooling.
     */
    public Set<String> keys(String pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (!codec.isKeyPrefix() || codec.isShared(pattern)) {
            return new LinkedHashSet<>(delegate.keys(pattern));
        }
        TenantId tenant = tenantForAccess();
        Set<String> raw = delegate.keys(codec.encode(pattern, tenant));
        Set<String> stripped = new LinkedHashSet<>(raw.size());
        for (String key : raw) {
            stripped.add(codec.decode(key, tenant));
        }
        return stripped;
    }

    /**
     * {@code SCAN} with the tenant rule applied to the string match pattern
     * and the inverse rule applied to every returned key (paired handling).
     * Without a string pattern, STRICT throws (a pattern-less scan would observe
     * the whole keyspace); LENIENT passes through <em>unrewritten and unstripped,
     * exposing every tenant's physical keys</em> &mdash; consistent with lenient's
     * philosophy, spelled out here because "no pattern" has no single key to
     * leave unprefixed.
     * The rewrite/strip pair is bound to the tenant at <em>call</em> time, so
     * lazily consumed cursor elements cannot leak a later context switch into
     * the iteration. Options without a string pattern (e.g. a raw
     * {@code byte[]} match) pass through unrewritten &mdash; the raw byte
     * form is the deliberate escape hatch.
     */
    public Cursor<String> scan(ScanOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        if (!codec.isKeyPrefix() || options.getPattern() == null || codec.isShared(options.getPattern())) {
            if (codec.isKeyPrefix() && options.getPattern() == null) {
                // a pattern-less scan would otherwise observe the WHOLE keyspace with
                // no policy check at all (strict must throw, not stream every tenant)
                tenantForAccess();
            }
            return delegate.scan(options);
        }
        TenantId tenant = tenantForAccess();
        ScanOptions rewritten = rewrite(options, pattern -> codec.encode(pattern, tenant));
        Cursor<String> raw = delegate.scan(rewritten);
        Function<String, String> strip = key -> codec.decode(key, tenant);
        return new Cursor<>() {

            @Override
            public boolean hasNext() {
                return raw.hasNext();
            }

            @Override
            public String next() {
                return strip.apply(raw.next());
            }

            @Override
            public void close() {
                raw.close();
            }

            @Override
            public long getPosition() {
                return raw.getPosition();
            }

            @Override
            public Cursor.CursorId getId() {
                return raw.getId();
            }

            @Override
            public long getCursorId() {
                return raw.getCursorId();
            }

            @Override
            public boolean isClosed() {
                return raw.isClosed();
            }
        };
    }

    private static ScanOptions rewrite(ScanOptions options, Function<String, String> rewritePattern) {
        ScanOptions.ScanOptionsBuilder builder = ScanOptions.scanOptions()
                .match(rewritePattern.apply(options.getPattern()));
        if (options.getCount() != null) {
            builder.count(options.getCount());
        }
        return builder.build();
    }

    /** Tenant-prefixed {@code DEL} for a single key. */
    public Boolean delete(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return delegate.delete(encodeKey(key));
    }

    /** Tenant-prefixed {@code DEL} for a batch of keys (batch maps atomically under one tenant). */
    public Long delete(Collection<String> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        Set<String> encoded = new LinkedHashSet<>(keys.size());
        for (String key : keys) {
            encoded.add(encodeKey(key));
        }
        return delegate.delete(encoded);
    }

    /** Tenant-prefixed {@code EXISTS} for a single key. */
    public Boolean hasKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return delegate.hasKey(encodeKey(key));
    }

    private String encodeKey(String key) {
        if (codec.isShared(key)) {
            // shared keys are exempt before policy resolution: they are precisely
            // the keys reachable without a tenant, STRICT included
            return key;
        }
        return codec.encode(key, tenantForAccess());
    }

    /**
     * Resolves the tenant for a non-shared key access, applying the
     * {@code no-context-policy}: STRICT throws {@link TenantMissingException}
     * (TK-1001), LENIENT warns and returns {@code null} (the codec then maps
     * the key unchanged). Shared keys never reach this method.
     */
    TenantId tenantForAccess() {
        if (!codec.isKeyPrefix()) {
            return null; // transparent delegate: no tenant semantics at all
        }
        TenantId tenant = TenantContext.currentOrNull();
        if (tenant != null) {
            return tenant;
        }
        if (properties.getNoContextPolicy() == TenantKitRedisProperties.NoContextPolicy.LENIENT) {
            LOG.warn("tenantkit.redis: no tenant bound to the current context; accessing Redis without the "
                    + "tenant prefix (tenantkit.redis.no-context-policy=lenient)");
            return null;
        }
        throw new TenantMissingException("tenantkit.redis: no tenant bound to the current context and "
                + "tenantkit.redis.no-context-policy=strict; bind a tenant (resolution filter or runAs/callAs), "
                + "add the key to tenantkit.redis.shared-keys, or set no-context-policy=lenient");
    }

    /** The wrapped native template &mdash; the documented, explicit opt-out surface. */
    public RedisTemplate<String, Object> getDelegate() {
        return delegate;
    }

    RedisTemplate<String, Object> delegate() {
        return delegate;
    }

    // ------------------------------------------------------------------------
    // All key-based ops families are covered (v0.1 full surface, R4 backlog #6):
    // every method delegates through a key-rewriting proxy; values/fields are
    // never inspected, so only declared key positions are touched.
    // ------------------------------------------------------------------------

    /** Tenant-aware hash operations (all key-taking methods rewritten). */
    public <HK, HV> org.springframework.data.redis.core.HashOperations<String, HK, HV> opsForHash() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.HashOperations.class,
                delegate.opsForHash(), this::encodeKey, key -> key);
    }

    /** Tenant-aware list operations (incl. {@code rightPopAndLeftPush}: both keys rewritten). */
    public org.springframework.data.redis.core.ListOperations<String, Object> opsForList() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.ListOperations.class,
                delegate.opsForList(), this::encodeKey, key -> key);
    }

    /** Tenant-aware set operations (move / intersect / union / *AndStore rewrite every key position). */
    public org.springframework.data.redis.core.SetOperations<String, Object> opsForSet() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.SetOperations.class,
                delegate.opsForSet(), this::encodeKey, key -> key);
    }

    /** Tenant-aware zset operations (incl. the {@code *AndStore} family). */
    public org.springframework.data.redis.core.ZSetOperations<String, Object> opsForZSet() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.ZSetOperations.class,
                delegate.opsForZSet(), this::encodeKey, key -> key);
    }

    /** Tenant-aware geo operations. */
    public org.springframework.data.redis.core.GeoOperations<String, Object> opsForGeo() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.GeoOperations.class,
                delegate.opsForGeo(), this::encodeKey, key -> key);
    }

    /** Tenant-aware HyperLogLog operations ({@code union} rewrites the source keys too). */
    public org.springframework.data.redis.core.HyperLogLogOperations<String, Object> opsForHyperLogLog() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.HyperLogLogOperations.class,
                delegate.opsForHyperLogLog(), this::encodeKey, key -> key);
    }

    /**
     * Tenant-aware stream operations. Stream keys are rewritten at every key
     * position ({@code StreamOffset}s in {@code read}/{@code readAll}, the key
     * argument of {@code range}/{@code add}/{@code delete}, records passed
     * directly); records returned by {@code range}/{@code reverseRange}/
     * {@code read} are re-keyed back to their logical form so internal prefixes
     * never leak into business code.
     */
    public <HK, HV> org.springframework.data.redis.core.StreamOperations<String, HK, HV> opsForStream() {
        return TenantOpsProxies.wrap(org.springframework.data.redis.core.StreamOperations.class,
                delegate.opsForStream(), this::encodeKey, key -> codec.decode(key, tenantForAccess()));
    }

    /** Bound value ops: the key is encoded once at binding time. */
    public org.springframework.data.redis.core.BoundValueOperations<String, Object> boundValueOps(String key) {
        return delegate.boundValueOps(encodeKey(key));
    }

    /** Bound hash ops: the key is encoded once at binding time. */
    public <HK, HV> org.springframework.data.redis.core.BoundHashOperations<String, HK, HV> boundHashOps(String key) {
        return delegate.boundHashOps(encodeKey(key));
    }

    /** Bound list ops: the key is encoded once at binding time. */
    public org.springframework.data.redis.core.BoundListOperations<String, Object> boundListOps(String key) {
        return delegate.boundListOps(encodeKey(key));
    }

    /** Bound set ops: the key is encoded once at binding time. */
    public org.springframework.data.redis.core.BoundSetOperations<String, Object> boundSetOps(String key) {
        return delegate.boundSetOps(encodeKey(key));
    }

    /** Bound zset ops: the key is encoded once at binding time. */
    public org.springframework.data.redis.core.BoundZSetOperations<String, Object> boundZSetOps(String key) {
        return delegate.boundZSetOps(encodeKey(key));
    }

    /** Bound geo ops: the key is encoded once at binding time. */
    public org.springframework.data.redis.core.BoundGeoOperations<String, Object> boundGeoOps(String key) {
        return delegate.boundGeoOps(encodeKey(key));
    }

    /** Bound stream ops: the key is encoded once at binding time. */
    public <HK, HV> org.springframework.data.redis.core.BoundStreamOperations<String, HK, HV> boundStreamOps(String key) {
        return delegate.boundStreamOps(encodeKey(key));
    }

    // ------------------------------------------------------------------------
    // Deliberate refusals — not feature gaps but safety boundaries: raw
    // callbacks and cluster-wide operations cannot be key-rewritten, and
    // pretending otherwise would be a lie about isolation.
    // ------------------------------------------------------------------------

    /** Raw callbacks bypass key rewriting by construction; refused on purpose. */
    public Object execute(RedisCallback<?> callback) {
        throw unsupported("execute");
    }

    /** Raw callbacks bypass key rewriting by construction; refused on purpose. */
    public Object execute(SessionCallback<?> callback) {
        throw unsupported("execute");
    }

    /** Pipelining runs raw callbacks; refused on purpose (see {@link #execute}). */
    public List<Object> executePipelined(SessionCallback<?> callback) {
        throw unsupported("executePipelined");
    }

    /** Pipelining runs raw callbacks; refused on purpose (see {@link #execute}). */
    public List<Object> executePipelined(RedisCallback<?> callback) {
        throw unsupported("executePipelined");
    }

    /** Cluster-wide operations are not tenant-scoped; use the raw template deliberately. */
    public org.springframework.data.redis.core.ClusterOperations<String, Object> opsForCluster() {
        throw unsupported("opsForCluster");
    }

    private static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("tenantkit-cache-redis refuses raw '" + operation + "' by design: "
                + "callbacks run outside the key-rewriting layer, so a tenant-prefixed view is impossible. "
                + "All key-based ops families are covered; inject the raw RedisTemplate (documented opt-in) "
                + "or use getDelegate() for deliberate unmanaged access.");
    }
}
