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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.util.AntPathMatcher;

import io.tenantkit.core.TenantId;

/**
 * The single place where tenant prefixes are added to or removed from Redis
 * key names (DESIGN &sect;6.4 / R5: the strip layer is unique &mdash; the
 * serializer never strips, only this codec, invoked from the
 * {@link TenantRedisTemplate} ops wrapper).
 *
 * <p>Prefix rule: a logical key {@code user:1} bound to tenant {@code alpha}
 * maps to the physical key {@code t:alpha:user:1}. Keys matching the
 * {@code sharedKeys} glob allow-list (Ant patterns, evaluated against the
 * full key string) are exempt in both directions: never prefixed, never
 * stripped &mdash; that is what makes cross-tenant shared data possible at
 * all.
 *
 * <p>The codec is a pure bidirectional function; the no-context policy
 * (STRICT throwing vs. LENIENT warning) is enforced by the calling template,
 * which passes {@code null} for "no tenant" when the policy allows it. A
 * {@code null} tenant always maps the key unchanged.
 */
public final class TenantKeyCodec {

    /** Literal prefix segment introducing the tenant namespace. */
    public static final String TENANT_SEGMENT = "t:";

    /** Separator between the tenant id and the logical key. */
    public static final String TENANT_SEPARATOR = ":";

    private final boolean keyPrefix;
    private final List<String> sharedKeys;
    /** Literal prefix of each shared pattern up to its first wildcard, for a cheap pre-check. */
    private final List<String> sharedKeyLiteralPrefixes;
    private final AntPathMatcher matcher = new AntPathMatcher();

    /**
     * @param keyPrefix  {@code false} turns the codec into an identity
     *                   function in both directions (the template degrades to
     *                   a transparent delegate)
     * @param sharedKeys Ant-style glob patterns; a key matching any pattern is
     *                   exempt from prefixing and stripping
     */
    public TenantKeyCodec(boolean keyPrefix, List<String> sharedKeys) {
        this.keyPrefix = keyPrefix;
        this.sharedKeys = sharedKeys == null ? List.of() : List.copyOf(sharedKeys);
        List<String> prefixes = new java.util.ArrayList<>(this.sharedKeys.size());
        for (String pattern : this.sharedKeys) {
            int wildcard = firstWildcardIndex(pattern);
            prefixes.add(wildcard < 0 ? pattern : pattern.substring(0, wildcard));
        }
        this.sharedKeyLiteralPrefixes = List.copyOf(prefixes);
    }

    /** Whether key rewriting is active at all ({@code tenantkit.redis.key-prefix}). */
    public boolean isKeyPrefix() {
        return keyPrefix;
    }

    /**
     * Whether {@code key} is exempt from rewriting (matches the
     * {@code sharedKeys} allow-list). Always {@code false} when prefixing is
     * disabled &mdash; nothing is rewritten, so nothing can be shared-exempt.
     */
    public boolean isShared(String key) {
        if (!keyPrefix || key == null) {
            return false;
        }
        for (int i = 0; i < sharedKeys.size(); i++) {
            // cheap literal-prefix pre-check: an Ant pattern can never match a key that
            // does not share its literal prefix. Without it every key access paid a full
            // AntPathMatcher pass (measured ~3x encode cost when globs are configured).
            if (!key.startsWith(sharedKeyLiteralPrefixes.get(i))) {
                continue;
            }
            if (matcher.match(sharedKeys.get(i), key)) {
                return true;
            }
        }
        return false;
    }

    private static int firstWildcardIndex(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '{') {
                return i;
            }
        }
        return -1;
    }

    /** The physical prefix for {@code tenant}, e.g. {@code t:alpha:}. */
    public String tenantPrefix(TenantId tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        return TENANT_SEGMENT + tenant.value() + TENANT_SEPARATOR;
    }

    /**
     * Logical key to physical key. Shared keys and {@code null}-tenant calls
     * map unchanged; everything else gets the {@code t:<tenant>:} prefix.
     */
    public String encode(String key, TenantId tenant) {
        Objects.requireNonNull(key, "key must not be null");
        if (!keyPrefix || tenant == null || isShared(key)) {
            return key;
        }
        return tenantPrefix(tenant) + key;
    }

    /**
     * Physical key back to logical key &mdash; the unique strip layer. Only
     * the prefix of the given tenant is removed; foreign-tenant keys and
     * shared keys pass through unchanged, so callers can never mistake
     * another tenant's data for their own unprefixed namespace.
     */
    public String decode(String key, TenantId tenant) {
        Objects.requireNonNull(key, "key must not be null");
        if (!keyPrefix || tenant == null || isShared(key)) {
            return key;
        }
        String prefix = tenantPrefix(tenant);
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    /** The configured allow-list, unmodifiable (diagnostics and tests). */
    public List<String> sharedKeys() {
        return new ArrayList<>(sharedKeys);
    }
}
