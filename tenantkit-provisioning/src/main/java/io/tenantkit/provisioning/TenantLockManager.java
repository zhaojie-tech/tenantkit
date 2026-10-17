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
package io.tenantkit.provisioning;

import java.time.Duration;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * Pluggable advisory lock around the whole provisioning run for one tenant
 * (ADR-0001 layer 1). The reference semantics are MySQL
 * {@code GET_LOCK}/{@code RELEASE_LOCK} &mdash; a <em>session-level</em> lock
 * bound to the physical connection that acquired it, which is why the default
 * implementation {@link MysqlGetLockManager} borrows one dedicated connection
 * via {@code ConnectionCallback}, acquires the lock on it, keeps it for the
 * whole run, releases on it in a {@code finally} block, and only then returns
 * the connection.
 *
 * <p>The seam exists for engines without {@code GET_LOCK} (H2, PostgreSQL
 * advisory locks with different syntax, test doubles). Providing your own bean
 * replaces the default ({@code @ConditionalOnMissingBean} in the
 * auto-configuration). Whatever the backend, the contract is strict:
 *
 * <ul>
 *   <li>mutually exclusive per tenant across instances (not just threads);</li>
 *   <li>blocking up to {@code timeout}, then failing with
 *       {@code TenantProvisioningException} code TK-2002 whose message contains
 *       "lock";</li>
 *   <li>release guaranteed on every exit path, including when
 *       {@link TenantLockAction#perform()} throws.</li>
 * </ul>
 *
 * <p>Note on pool capacity (ADR-0001 consequences): while provisioning runs,
 * one connection is held for the lock while scripts open their own
 * transactions &mdash; size the pool for at least two concurrent connections
 * per concurrent provisioning.
 */
public interface TenantLockManager {

    /**
     * Runs the action while holding the tenant's provisioning lock.
     *
     * @param tenant tenant whose provisioning is mutually excluded
     * @param timeout maximum wait for lock acquisition
     * @param action the provisioning body (executes seed scripts, touches the
     *        ledger)
     * @param <T> action result type
     * @return the action's result
     * @throws TenantProvisioningException TK-2002 when the lock cannot be
     *         acquired within the timeout
     */
    <T> T withTenantLock(TenantId tenant, Duration timeout, TenantLockAction<T> action);

    /** The mutually excluded provisioning body. */
    @FunctionalInterface
    interface TenantLockAction<T> {

        /** Runs under the tenant lock; runtime exceptions release the lock. */
        T perform();
    }
}
