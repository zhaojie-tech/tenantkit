package io.tenantkit.provisioning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MysqlGetLockManager} protocol, mocked at the JDBC edge so the lock
 * semantics are covered in the fast tier; real {@code GET_LOCK} contention
 * lives in the MySQL container test (slow tier).
 */
class MysqlGetLockManagerTest {

    /** The connection the mocked JdbcTemplate hands to every ConnectionCallback. */
    private static final ThreadLocal<Connection> CALLBACK_CONNECTION = new ThreadLocal<>();

    private PreparedStatement getLockStatement;
    private PreparedStatement releaseLockStatement;
    private MysqlGetLockManager lockManager;

    @org.junit.jupiter.api.AfterEach
    void clearCallbackBinding() {
        CALLBACK_CONNECTION.remove();
    }

    @BeforeEach
    void setUpMocks() throws Exception {
        Connection connection = mock(Connection.class);
        getLockStatement = mock(PreparedStatement.class);
        releaseLockStatement = mock(PreparedStatement.class);

        ResultSet getLockResult = mock(ResultSet.class);
        ResultSet releaseResult = mock(ResultSet.class);
        when(getLockResult.next()).thenReturn(true);
        when(getLockResult.getObject(1)).thenReturn(1L);
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getObject(1)).thenReturn(1L);
        when(getLockStatement.executeQuery()).thenReturn(getLockResult);
        when(releaseLockStatement.executeQuery()).thenReturn(releaseResult);
        when(connection.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(getLockStatement);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseLockStatement);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(CALLBACK_CONNECTION.get());
        });
        CALLBACK_CONNECTION.set(connection);
        lockManager = new MysqlGetLockManager(jdbcTemplate);
    }

    @Test
    void lockNameIsTenantScopedAndStable() {
        assertThat(MysqlGetLockManager.lockName(TenantId.of("tenant-a")))
                .isEqualTo("tenantkit:provision:tenant-a");
    }

    @Test
    void executesActionBetweenGetLockAndReleaseLockOnSameConnection() throws Exception {
        String result = lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(5), () -> "done");

        assertThat(result).isEqualTo("done");
        verify(getLockStatement).setObject(1, "tenantkit:provision:tenant-a");
        verify(getLockStatement).setObject(2, 5L);
        verify(releaseLockStatement).setObject(1, "tenantkit:provision:tenant-a");
        verify(releaseLockStatement, times(1)).executeQuery();
    }

    /** R4 P2 fix lock-in: positive sub-second durations clamp UP to 1s, never down to "no wait". */
    @Test
    void subSecondTimeoutClampsToOneSecond() throws Exception {
        String result = lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofMillis(500), () -> "ok");
        assertThat(result).isEqualTo("ok");
        verify(getLockStatement).setObject(2, 1L);
    }

    /** null timeout keeps MySQL's documented no-wait(0) meaning. */
    @Test
    void nullTimeoutPassesZero() throws Exception {
        lockManager.withTenantLock(TenantId.of("tenant-a"), null, () -> "ok");
        verify(getLockStatement).setObject(2, 0L);
    }

    /** R4 P3: a release failure must not replace the in-flight outcome. */
    @Test
    void releaseFailureDoesNotReplaceActionResult() throws Exception {
        when(releaseLockStatement.executeQuery()).thenThrow(new java.sql.SQLException("release boom"));
        String result = lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(5), () -> "still-done");
        assertThat(result).isEqualTo("still-done");
    }

    /** R4: non-MySQL engines must see the documented remedy, not a raw bad-SQL error. */
    @Test
    void getLockUnavailableIsTranslatedToTk2002WithRemedy() throws Exception {
        when(getLockStatement.executeQuery()).thenThrow(new java.sql.SQLException("Function \"GET_LOCK\" not found"));

        assertThatThrownBy(() -> lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(5),
                () -> "never")).isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2002");
                    assertThat(ex.getMessage())
                            .contains("GET_LOCK")
                            .contains("MySQL")
                            .contains("TenantLockManager");
                });
    }

    @Test
    void zeroFromGetLockTimesOutWithTk2002() throws Exception {
        ResultSet timeoutResult = mock(ResultSet.class);
        when(timeoutResult.next()).thenReturn(true);
        when(timeoutResult.getObject(1)).thenReturn(0L);
        when(getLockStatement.executeQuery()).thenReturn(timeoutResult);

        assertThatThrownBy(() -> lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(1),
                () -> "never")).isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2002");
                    assertThat(ex.getMessage()).contains("lock").contains("tenantkit:provision:tenant-a");
                });
        // timed out before holding: never released (nothing to release)
        verify(releaseLockStatement, times(0)).executeQuery();
    }

    @Test
    void nullFromGetLockIsAlsoALockFailure() throws Exception {
        ResultSet errorResult = mock(ResultSet.class);
        when(errorResult.next()).thenReturn(true);
        when(errorResult.getObject(1)).thenReturn(null);
        when(getLockStatement.executeQuery()).thenReturn(errorResult);

        assertThatThrownBy(() -> lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(1),
                () -> "never")).isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("TK-2002");
                    assertThat(ex.getMessage()).contains("lock");
                });
    }

    /** ADR-0001 layer 3: the release must run even when the action explodes. */
    @Test
    void actionFailureStillReleasesLockOnTheSameConnection() throws Exception {
        assertThatThrownBy(() -> lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("script blew up");
        })).isInstanceOf(IllegalStateException.class);

        verify(releaseLockStatement, times(1)).setObject(1, "tenantkit:provision:tenant-a");
        verify(releaseLockStatement, times(1)).executeQuery();
    }

    @Test
    void unexpectedReleaseResultWarnsButDoesNotThrow() throws Exception {
        ResultSet warnResult = mock(ResultSet.class);
        when(warnResult.next()).thenReturn(true);
        when(warnResult.getObject(1)).thenReturn(0L);
        when(releaseLockStatement.executeQuery()).thenReturn(warnResult);

        assertThat(lockManager.withTenantLock(TenantId.of("tenant-a"), Duration.ofSeconds(5), () -> "ok"))
                .isEqualTo("ok");
        verify(releaseLockStatement).executeQuery();
    }

    @Test
    void nullTimeoutIsTreatedAsNoWait() throws Exception {
        lockManager.withTenantLock(TenantId.of("tenant-a"), null, () -> "ok");
        verify(getLockStatement).setObject(2, 0L);
    }
}
