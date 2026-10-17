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
import java.util.concurrent.Callable;

import io.tenantkit.core.exception.TenantMissingException;

/**
 * Explicitly scoped tenant context. There is deliberately no public setter or
 * clearer: the only way to bind a tenant is the structured
 * {@link #runAs(TenantId, Runnable)} / {@link #callAs(TenantId, Callable)}
 * scope, which restores the <em>outer</em> value on exit instead of clearing
 * (the hand-rolled {@code finally { clear() }} that wipes the caller's context
 * is exactly the incident class this API exists to prevent, DESIGN &sect;6.1).
 *
 * <p>Backing storage is a plain {@link ThreadLocal} &mdash;
 * {@link InheritableThreadLocal} is explicitly not used, because implicit
 * inheritance at thread-creation time is the enemy of explicit propagation and
 * a trap for virtual-thread adoption. Cross-thread propagation is always
 * explicit: the task decorator (web/messaging modules), or
 * {@code io.micrometer:context-propagation} when present, or
 * {@code TenantExecutors.wrap} (in this module).
 *
 * <p>When {@code io.micrometer:context-propagation} is on the classpath, a
 * Micrometer {@code ThreadLocalAccessor} for key
 * {@value TenantContextPropagation#CONTEXT_KEY} is registered so
 * {@code ContextSnapshot} captures/restores the tenant (DESIGN &sect;6.3).
 * Without it the kit falls back to its manual decorator &mdash; behavior is
 * identical (matrix K03/K04).
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Returns the tenant bound to the current thread.
     *
     * @throws TenantMissingException when no tenant is bound (fail-fast;
     *         silently returning a default tenant is how cross-tenant leaks
     *         are born)
     */
    public static TenantId required() {
        TenantId tenant = CURRENT.get();
        if (tenant == null) {
            throw new TenantMissingException(
                    "no tenant bound to the current context; resolution must run first or the scope must use runAs/callAs");
        }
        return tenant;
    }

    /** Returns the bound tenant or {@code null} &mdash; for optional-tenant code paths. */
    public static TenantId currentOrNull() {
        return CURRENT.get();
    }

    /**
     * Runs {@code action} with {@code id} bound, restoring the previous binding
     * afterwards (nested scopes restore their outer value; the outermost exit
     * leaves the context empty).
     */
    public static void runAs(TenantId id, Runnable action) {
        Objects.requireNonNull(id, "tenant id must not be null");
        Objects.requireNonNull(action, "action must not be null");
        TenantId previous = CURRENT.get();
        CURRENT.set(id);
        try {
            action.run();
        }
        finally {
            restore(previous);
        }
    }

    /**
     * Calls {@code action} with {@code id} bound, restoring the previous
     * binding afterwards. Exceptions propagate to the caller with the context
     * already restored.
     */
    public static <T> T callAs(TenantId id, Callable<T> action) throws Exception {
        Objects.requireNonNull(id, "tenant id must not be null");
        Objects.requireNonNull(action, "action must not be null");
        TenantId previous = CURRENT.get();
        CURRENT.set(id);
        try {
            return action.call();
        }
        finally {
            restore(previous);
        }
    }

    /**
     * Package-private binding hook for the Micrometer accessor only. Not part
     * of the public API; the ArchUnit gate forbids public mutators on this
     * class (DESIGN &sect;8).
     */
    static void unsafeSet(TenantId value) {
        if (value == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(value);
        }
    }

    private static void restore(TenantId previous) {
        if (previous == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(previous);
        }
    }
}
