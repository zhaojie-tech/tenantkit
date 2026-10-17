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

import io.micrometer.context.ContextRegistry;

/**
 * Micrometer integration for {@link TenantContext}. Loaded only when
 * {@code io.micrometer:context-propagation} is on the classpath (guarded by
 * {@link #isAvailable()}); without the dependency the kit falls back to its
 * manual decorator with identical behavior (DESIGN &sect;6.3, matrix K03/K04).
 */
public final class TenantContextPropagation {

    /** Registry key of the TenantKit accessor. */
    public static final String CONTEXT_KEY = "tenantkit.tenant";

    private static volatile boolean installed;

    private TenantContextPropagation() {
    }

    /** {@code true} when {@code io.micrometer:context-propagation} is loadable. */
    public static boolean isAvailable() {
        try {
            Class.forName("io.micrometer.context.ContextRegistry", false,
                    TenantContextPropagation.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Registers the accessor exactly once. Safe to call repeatedly; later calls
     * are no-ops.
     *
     * @throws IllegalStateException when context-propagation is not on the classpath
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "io.micrometer:context-propagation is not on the classpath; TenantKit falls back to its manual decorator");
        }
        MicrometerBridge.install();
        installed = true;
    }

    /** {@code true} once {@link #install()} has run. */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Test-only hook: drops the registration and forgets install state.
     *
     * <p>Package-private on purpose (R4 backlog #10): the registration is a
     * <em>process-global</em> singleton (micrometer's ContextRegistry is JVM-wide),
     * so exposing {@code reset()} publicly would let one context tear down the
     * bridge for every other context in the same JVM. Production code never
     * calls this; install/uninstall symmetry across contexts is unsafe by
     * construction, not an omission.
     */
    static synchronized void reset() {
        if (installed) {
            MicrometerBridge.remove();
            installed = false;
        }
    }

    /**
     * Only class referencing context-propagation types beyond the presence
     * check. JVM verifies a method's bytecode on first invocation, so touching
     * micrometer types directly inside {@code install()} would defeat the
     * availability guard; routing through this class defers verification until
     * the guard has passed (same pattern as matrix K04 proves).
     */
    private static final class MicrometerBridge {

        private MicrometerBridge() {
        }

        static void install() {
            ContextRegistry.getInstance().registerThreadLocalAccessor(new TenantKitThreadLocalAccessor());
        }

        static void remove() {
            ContextRegistry.getInstance().removeThreadLocalAccessor(CONTEXT_KEY);
        }
    }
}
