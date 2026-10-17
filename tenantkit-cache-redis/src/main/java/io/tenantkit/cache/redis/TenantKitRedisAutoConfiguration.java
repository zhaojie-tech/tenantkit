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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;

/**
 * Cache-redis auto-configuration (DESIGN &sect;6.4, assembly speed table
 * &sect;5). Guarded by {@code spring-data-redis} on the classpath and the
 * module switch {@code tenantkit.redis.enabled}; both beans also require a
 * {@link RedisTemplate} bean so the module backs off entirely in a Redis-less
 * context.
 *
 * <p>{@code tenantRedisTemplate} is deliberately <strong>not</strong>
 * {@code @Primary} (DESIGN &sect;6.4 opt-in declaration): injecting the raw
 * {@code RedisTemplate} keeps bypassing tenant rewriting by design &mdash;
 * the escape hatch for shared infrastructure keys is dependency injection,
 * and a primary tenant template would silently rewrite keys that must not be
 * rewritten.
 */
@AutoConfiguration(afterName = {
        // Boot 4 renamed/moved the redis autoconfig; without this the @ConditionalOnBean
        // (RedisTemplate) below is evaluated BEFORE Boot creates redisTemplate and the whole
        // module silently no-ops for every consumer (R4 consumer P0). afterName is a string,
        // so the annotation stays loadable even where the class does not exist.
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@ConditionalOnClass(RedisOperations.class)
@ConditionalOnProperty(name = "tenantkit.redis.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantKitRedisProperties.class)
public class TenantKitRedisAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TenantKitRedisAutoConfiguration.class);

    /**
     * Wraps the container's {@code RedisTemplate<String, Object>} with the
     * tenant key codec. Requires a template bean of matching generic types;
     * when only differently parameterized templates exist the bean is not
     * assembled and a warning names the remedy.
     */
    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    @ConditionalOnMissingBean(TenantRedisTemplate.class)
    public TenantRedisTemplate tenantRedisTemplate(ObjectProvider<RedisTemplate<String, Object>> delegate,
            org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> stringDelegate,
            TenantKitRedisProperties properties) {
        RedisTemplate<String, Object> raw = delegate.getIfAvailable();
        if (raw == null) {
            // Boot's out-of-the-box beans are RedisTemplate<Object,Object> and
            // StringRedisTemplate; generics matching misses BOTH. StringRedisTemplate
            // is a natural fit (String keys) — adapt it before giving up.
            org.springframework.data.redis.core.StringRedisTemplate stringTemplate =
                    stringDelegate.getIfAvailable();
            if (stringTemplate != null) {
                LOG.info("tenantkit.redis: wrapping Boot's stringRedisTemplate (no RedisTemplate<String, Object> bean found)");
                @SuppressWarnings({ "unchecked", "rawtypes" })
                RedisTemplate<String, Object> adapted = (RedisTemplate) stringTemplate;
                return new TenantRedisTemplate(adapted, properties);
            }
            LOG.warn("tenantkit.redis: no RedisTemplate<String, Object> or StringRedisTemplate bean found; "
                    + "tenantRedisTemplate is not assembled. Declare one or inject your own template directly.");
            return null; // NullBean: not registered; the context still starts
        }
        return new TenantRedisTemplate(raw, properties);
    }

    /**
     * Warns loudly when the module is enabled but no {@code tenantRedisTemplate}
     * materialized (missing template bean, missing spring-data-redis, wrong
     * ordering): a silent no-op is how "isolation assumed but absent" incidents
     * start (R4 consumer P0-2 follow-up).
     */
    @Bean
    static org.springframework.beans.factory.SmartInitializingSingleton tenantKitRedisAssemblyProbe(
            org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        return () -> {
            if (beanFactory.getBeanNamesForType(TenantRedisTemplate.class).length == 0) {
                LOG.warn("tenantkit.redis is enabled but no TenantRedisTemplate bean was assembled: "
                        + "key prefixing and isolation are NOT active. Ensure a RedisTemplate or "
                        + "stringRedisTemplate bean exists (spring-boot-starter-data-redis) and that "
                        + "this auto-configuration runs after Boot's redis auto-configuration.");
            }
        };
    }

    /**
     * Tenant cache key prefix for {@code RedisCacheManager}; skipped when the
     * user supplies their own {@link org.springframework.data.redis.cache.CacheKeyPrefix}
     * bean. Missing context always computes a tenant-less prefix (startup
     * prewarm must never fail &mdash; pinned in DESIGN &sect;6.4).
     */
    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    @ConditionalOnMissingBean(org.springframework.data.redis.cache.CacheKeyPrefix.class)
    public TenantCacheKeyPrefix tenantCacheKeyPrefix() {
        return new TenantCacheKeyPrefix();
    }
}
