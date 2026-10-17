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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * Default {@link TenantLockManager}: MySQL named advisory lock
 * ({@code GET_LOCK}/{@code RELEASE_LOCK}) executed on one dedicated physical
 * connection for the whole provisioning run (ADR-0001 layer 1).
 *
 * <p>The connection is borrowed through
 * {@link JdbcTemplate#execute(ConnectionCallback)} and never returned to the
 * pool until the callback finishes, so the session-level lock cannot detach
 * from the connection that owns it &mdash; the two classic HikariCP incidents
 * (cross-connection lock = no mutual exclusion, forgotten release = permanent
 * lock leak) are both structurally impossible here. {@code RELEASE_LOCK} runs
 * in the {@code finally} block on the same connection, on every exit path.
 *
 * <p>{@code GET_LOCK} blocks up to the given timeout and returns 0 on timeout
 * (or {@code NULL} on error) &mdash; both surface as
 * {@code TenantProvisioningException} code TK-2002 with a message naming the
 * lock. Negative timeouts mean "wait forever" on MySQL 5.7.5+; prefer a finite
 * value. Concurrency semantics (two instances contending for the same lock,
 * lock not leaking after failures) are pinned by the container test
 * {@code MysqlGetLockConcurrencyTest} (slow tier).
 */
public class MysqlGetLockManager implements TenantLockManager {

    private static final Logger log = LoggerFactory.getLogger(MysqlGetLockManager.class);

    /** GET_LOCK result: acquired. */
    private static final long ACQUIRED = 1L;

    private final JdbcTemplate jdbcTemplate;

    public MysqlGetLockManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** MySQL lock name for the tenant's provisioning run. */
    static String lockName(TenantId tenant) {
        return "tenantkit:provision:" + tenant.value();
    }

    @Override
    public <T> T withTenantLock(TenantId tenant, Duration timeout, TenantLockAction<T> action) {
        String lockName = lockName(tenant);
        // GET_LOCK semantics: 0 = do not wait, N = wait N seconds, negative = INFINITE.
        // A positive sub-second Duration used to truncate to 0 (= no wait at all), so
        // "wait half a second" silently became "fail instantly" (R4 P2). Positive
        // durations therefore clamp UP to 1s; an explicit zero keeps its no-wait meaning.
        long timeoutSeconds;
        if (timeout == null) {
            timeoutSeconds = 0L;
        }
        else if (!timeout.isZero() && timeout.getSeconds() == 0) {
            timeoutSeconds = 1L;
        }
        else {
            timeoutSeconds = timeout.toSeconds();
        }
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            long result;
            try {
                result = queryScalar(connection, "SELECT GET_LOCK(?, ?)", lockName, timeoutSeconds);
            }
            catch (SQLException lockQueryFailure) {
                // engines without GET_LOCK (H2, PostgreSQL, ...) surface as an opaque
                // "bad SQL grammar" here; translate into the actionable TK contract
                // (R4 consumer P1-2 ergonomics fix)
                throw new TenantProvisioningException("TK-2002",
                        "GET_LOCK is unavailable on this database connection: "
                                + lockQueryFailure.getMessage()
                                + ". The default TenantLockManager requires MySQL; register your own "
                                + "TenantLockManager bean for other databases (it replaces the default).",
                        lockQueryFailure);
            }
            if (result != ACQUIRED) {
                throw new TenantProvisioningException("TK-2002",
                        "could not acquire provisioning lock '" + lockName + "' within " + timeoutSeconds
                                + "s (GET_LOCK returned " + result
                                + "; 0 = another instance holds the lock, NULL = lock error)");
            }
            try {
                return action.perform();
            }
            finally {
                try {
                    release(connection, lockName);
                }
                catch (SQLException releaseFailure) {
                    // never let a release failure replace the real provisioning outcome
                    log.warn("RELEASE_LOCK({}) failed: {}", lockName, releaseFailure.getMessage());
                }
            }
        });
    }

    /**
     * Releases on the same physical connection that holds the lock; guaranteed
     * to run on every exit path because it sits in the caller's finally block.
     */
    private void release(Connection connection, String lockName) throws SQLException {
        long result = queryScalar(connection, "SELECT RELEASE_LOCK(?)", lockName);
        if (result != ACQUIRED) {
            // 0 = not owner, NULL = lock did not exist; log-worthy but never fatal
            log.warn("RELEASE_LOCK({}) returned {} on release - lock was not owned by this session", lockName,
                    result);
        }
    }

    private long queryScalar(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                Object value = resultSet.getObject(1);
                return value == null ? -1L : ((Number) value).longValue();
            }
        }
    }
}
