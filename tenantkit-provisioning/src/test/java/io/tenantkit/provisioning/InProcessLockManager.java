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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * JVM-local lock manager for the fast (H2) tier: H2 has no {@code GET_LOCK},
 * so tests inject this process-wide semaphore lock that mirrors the MySQL
 * contract (blocking try-acquire with timeout, release in finally, TK-2002 on
 * timeout). Real cross-instance semantics are pinned by the MySQL container
 * test ({@code MysqlGetLockConcurrencyTest}, slow tier).
 *
 * <p>Not shipped in main sources: a no-op lock would silently drop mutual
 * exclusion in production &mdash; only an engine-equivalent implementation
 * belongs there.
 */
final class InProcessLockManager implements TenantLockManager {

    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withTenantLock(TenantId tenant, Duration timeout, TenantLockAction<T> action) {
        Semaphore lock = locks.computeIfAbsent(MysqlGetLockManager.lockName(tenant), key -> new Semaphore(1));
        boolean acquired;
        try {
            acquired = lock.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TenantProvisioningException("TK-2002",
                    "interrupted while waiting for provisioning lock of tenant " + tenant + " (lock)", interrupted);
        }
        if (!acquired) {
            throw new TenantProvisioningException("TK-2002",
                    "timed out after " + timeout.toMillis() + "ms waiting for provisioning lock of tenant " + tenant
                            + "; another provisioning for this tenant is in progress (lock)");
        }
        try {
            return action.perform();
        }
        finally {
            lock.release();
        }
    }

    /** Test hook: holds the tenant lock from the calling thread until released. */
    <T> T holdWhile(TenantId tenant, Duration timeout, TenantLockAction<T> action) {
        return withTenantLock(tenant, timeout, action);
    }

    /** Test hook: {@code true} while another thread currently holds the tenant lock. */
    boolean holdsPermit(TenantId tenant) {
        Semaphore lock = locks.get(MysqlGetLockManager.lockName(tenant));
        return lock != null && lock.availablePermits() == 0;
    }
}
