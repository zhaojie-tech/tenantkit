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
package io.tenantkit.core.async;

import java.util.Map;

import org.slf4j.MDC;

/**
 * MDC capture/restore helper so every propagation path (task decorator, MQ
 * advice, TenantExecutors) keeps the observation promise consistent: a tenant
 * restored without its MDC would make the log line lie (DESIGN &sect;6.3/§7).
 */
public final class TenantMdc {

    /** MDC key the resolution filter writes; the decorator restores it. */
    public static final String TENANT_KEY = "tenantId";

    private TenantMdc() {
    }

    /** Snapshot of the current MDC contents (possibly empty, never {@code null}). */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap() == null ? Map.of() : MDC.getCopyOfContextMap();
    }

    /** Replaces the current MDC with {@code context} (empty clears). */
    public static void restore(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(context);
    }
}
