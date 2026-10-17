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
package io.tenantkit.core;

import java.util.Objects;
import java.util.regex.Pattern;

import io.tenantkit.core.exception.TenantIdFormatException;

/**
 * Immutable tenant identifier. Validation happens in the compact canonical
 * constructor, so the public constructor cannot be bypassed (DESIGN &sect;6.1).
 *
 * <p>Default charset is {@code [a-z0-9-]{1,32}}: lowercase letters, digits and
 * dashes. The charset guards against injection into SQL fragments, Redis keys
 * and log lines. The pattern may be relaxed at startup via
 * {@link #usePattern(String)} &mdash; relaxation re-opens an injection surface;
 * the provisioning placeholder replacement must then switch to parameterized
 * binding or allow-list escaping (DESIGN &sect;6.5 / risk R3).
 *
 * <p>{@code toString()} deliberately returns the bare value without quotes or
 * braces so tenant ids stay log-injection-safe (DESIGN &sect;7).
 */
public record TenantId(String value) {

    private static final String DEFAULT_PATTERN = "[a-z0-9-]{1,32}";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TenantId.class);

    /**
     * Startup-configurable validation pattern. Written once by the resolving
     * auto-configuration before any request is served; read on every
     * construction. Volatile so worker threads see the configured value.
     */
    private static volatile Pattern activePattern = Pattern.compile(DEFAULT_PATTERN);

    /**
     * Compact canonical constructor &mdash; the single validation point.
     *
     * @throws TenantIdFormatException if {@code value} is {@code null} or does
     *         not match the active pattern
     */
    public TenantId {
        Objects.requireNonNull(value, "tenant id value must not be null");
        if (!activePattern.matcher(value).matches()) {
            throw new TenantIdFormatException(
                    "tenant id '" + value + "' does not match pattern '" + activePattern.pattern() + "'");
        }
    }

    /** Factory mirroring the record constructor, for fluent call sites. */
    public static TenantId of(String raw) {
        return new TenantId(raw);
    }

    /**
     * Replaces the startup validation pattern. Intended to be called exactly
     * once, by the resolving auto-configuration, before any tenant id is
     * constructed. Relaxing the default charset re-opens an injection surface;
     * read DESIGN &sect;6.5 before widening it.
     */
    public static void usePattern(String regex) {
        Pattern previous = activePattern;
        activePattern = Pattern.compile(regex);
        // the pattern is process-global: a second Spring context (tests, multiple
        // SpringApplication instances) that rewrites a DIFFERENT non-default pattern
        // widens or narrows validation for every context in the JVM. Last-writer
        // wins, but never silently (R4 P2).
        if (!previous.pattern().equals(regex)
                && !DEFAULT_PATTERN.equals(previous.pattern())) {
            LOG.warn("tenant tenant-id charset pattern changed from '{}' to '{}' by another context in this JVM; "
                    + "the previous context now validates against the new pattern. Avoid running multiple "
                    + "contexts with different tenantkit.resolution.tenant-id-pattern values in one JVM.",
                    previous.pattern(), regex);
        }
    }

    /** Resets to the default pattern (used by tests and re-configuration). */
    public static void resetPattern() {
        activePattern = Pattern.compile(DEFAULT_PATTERN);
    }

    /** The currently active validation pattern (exposed for diagnostics). */
    public static String activePattern() {
        return activePattern.pattern();
    }

    /**
     * Returns the bare value without quotes or braces &mdash; overriding the
     * record-generated form keeps tenant ids log-injection-safe (DESIGN &sect;7).
     */
    @Override
    public String toString() {
        return value;
    }
}
