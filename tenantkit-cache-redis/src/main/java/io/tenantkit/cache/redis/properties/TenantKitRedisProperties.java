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
package io.tenantkit.cache.redis.properties;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the cache-redis module (DESIGN &sect;6.4 yaml block).
 * Bound under {@code tenantkit.redis}; values are consumed by
 * {@link io.tenantkit.cache.redis.TenantKeyCodec} and
 * {@link io.tenantkit.cache.redis.TenantRedisTemplate}.
 */
@ConfigurationProperties(prefix = "tenantkit.redis")
public class TenantKitRedisProperties {

    /**
     * Master switch; {@code false} disables the module's auto-configuration
     * entirely, user beans are unaffected (DESIGN &sect;5 speed table).
     */
    private boolean enabled = true;

    /**
     * Whether keys are rewritten to {@code t:<tenant>:...}. {@code false}
     * degrades {@code tenantRedisTemplate} to a transparent delegate: no
     * rewriting, no stripping, no no-context policy &mdash; the documented
     * opt-out for shared-infrastructure jobs.
     */
    private boolean keyPrefix = true;

    /**
     * Ant-style glob allow-list (e.g. {@code app:*}); keys matching a pattern
     * are never prefixed and never stripped &mdash; cross-tenant shared data.
     * Shared keys are also the only keys reachable without a bound tenant
     * while the policy is {@link NoContextPolicy#STRICT}.
     */
    private List<String> sharedKeys = new ArrayList<>();

    /**
     * What happens when a (non-shared) key is accessed with no tenant bound:
     * {@code strict} throws {@code TenantMissingException} (TK-1001, the
     * fail-fast default &mdash; a silent default tenant is how cross-tenant
     * leaks are born), {@code lenient} passes the key through unprefixed and
     * logs a warning. Startup paths that compute cache prefixes are exempt:
     * see {@link io.tenantkit.cache.redis.TenantCacheKeyPrefix}.
     */
    private NoContextPolicy noContextPolicy = NoContextPolicy.STRICT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(boolean keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public List<String> getSharedKeys() {
        return sharedKeys;
    }

    public void setSharedKeys(List<String> sharedKeys) {
        this.sharedKeys = sharedKeys;
    }

    public NoContextPolicy getNoContextPolicy() {
        return noContextPolicy;
    }

    public void setNoContextPolicy(NoContextPolicy noContextPolicy) {
        this.noContextPolicy = noContextPolicy;
    }

    /**
     * Behavior for key access without a bound tenant. Bound (case
     * insensitively) from the documented values {@code strict} /
     * {@code lenient}; the lowercase codes are the YAML-facing spelling.
     */
    public enum NoContextPolicy {

        /** Fail fast with {@code TenantMissingException} (TK-1001). Default. */
        STRICT("strict"),

        /** Pass the key through unprefixed and log a warning. */
        LENIENT("lenient");

        private final String code;

        NoContextPolicy(String code) {
            this.code = code;
        }

        /** The YAML-facing value, e.g. {@code strict}. */
        public String getCode() {
            return code;
        }
    }
}
