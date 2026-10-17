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

import org.springframework.data.redis.cache.CacheKeyPrefix;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;

/**
 * {@link CacheKeyPrefix} for {@code RedisCacheManager} (DESIGN &sect;6.4):
 * runtime cache entries land under {@code t:<tenant>:<cacheName>:} so the
 * Spring Cache abstraction is tenant-scoped without touching value
 * serializers.
 *
 * <p>DESIGN &sect;6.4, pinned sentence (do not fork): <em>the startup path
 * &mdash; {@code RedisCacheManager} preload/prewarm invokes
 * {@link #compute(String)} with no tenant bound &mdash; is always treated
 * leniently</em>; {@code compute} therefore never throws, and a missing
 * tenant yields the tenant-less prefix {@code <cacheName>:}. The
 * {@code tenantkit.redis.no-context-policy} governs runtime
 * {@link TenantRedisTemplate} access only, never this method: honoring
 * STRICT here would detonate application startup.
 */
public final class TenantCacheKeyPrefix implements CacheKeyPrefix {

    /** Same literal namespace as {@link TenantKeyCodec#TENANT_SEGMENT}. */
    static final String TENANT_SEGMENT = "t:";

    @Override
    public String compute(String cacheName) {
        TenantId tenant = TenantContext.currentOrNull();
        if (tenant == null) {
            // startup path: fixed lenient (DESIGN §6.4) — prefix without a tenant segment
            return cacheName + ":";
        }
        return TENANT_SEGMENT + tenant.value() + ":" + cacheName + ":";
    }
}
