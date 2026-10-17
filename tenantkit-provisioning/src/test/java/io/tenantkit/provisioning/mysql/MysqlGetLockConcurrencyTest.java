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
package io.tenantkit.provisioning.mysql;

import java.sql.Driver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantProvisioningException;
import io.tenantkit.provisioning.DefaultTenantProvisioner;
import io.tenantkit.provisioning.MysqlGetLockManager;
import io.tenantkit.provisioning.ProvisionOptions;
import io.tenantkit.provisioning.ProvisionReport;
import io.tenantkit.provisioning.SeedLedger;
import io.tenantkit.provisioning.SeedProfile;
import io.tenantkit.provisioning.SeedScriptLocator;
import io.tenantkit.test.TenantSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real {@code GET_LOCK} concurrency semantics against the shared MySQL
 * container (slow tier: {@code mvn verify -Pcontainers}, DESIGN &sect;9
 * fast/slow split). Covers the cases DESIGN &sect;6.5 item 6 defers to 0.1.1:
 * <b>0.1.1 issue 登记</b> &mdash; docs/BACKLOG-0.1.1.md #2（供给并发竞争用例，
 * §11 砍线滑 0.1.1，issue 挂名登记不静默消失）.
 *
 * <p>Requires Docker and the MySQL JDBC driver on the test classpath
 * ({@code com.mysql:mysql-connector-j}, deliberately not part of the frozen
 * provisioning POM); both missing pieces abort the class via assumptions, so
 * the default fast build never touches it.
 */
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlGetLockConcurrencyTest {

    private static final TenantId TENANT = TenantId.of("concurrent-tenant");

    private JdbcTemplate jdbc;
    private DefaultTenantProvisioner provisioner;
    private MysqlGetLockManager lockManager;

    @BeforeAll
    void startContainerAndWireProvisioner() throws Exception {
        Assumptions.assumeTrue(mysqlDriverPresent(), "MySQL JDBC driver not on test classpath");
        var container = TenantSupport.mysql();
        Driver driver = (Driver) Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance();
        DataSource dataSource = new SimpleDriverDataSource(driver, container.getJdbcUrl(), container.getUsername(),
                container.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // application-side migrations (ADR-0001: the kit never creates the ledger)
        jdbc.execute(SeedLedger.REFERENCE_DDL);
        jdbc.execute("CREATE TABLE IF NOT EXISTS tenant_notes ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, note VARCHAR(64) NOT NULL)");
        jdbc.update("DELETE FROM tenantkit_seed_history");
        jdbc.update("DELETE FROM tenant_notes WHERE tenant_id = ?", TENANT.value());

        lockManager = new MysqlGetLockManager(jdbc);
        provisioner = new DefaultTenantProvisioner(new SeedScriptLocator("classpath:db/tenant-seed/"),
                new SeedLedger(jdbc, "tenantkit_seed_history"), lockManager,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), jdbc,
                Duration.ofSeconds(30), false);
    }

    private static boolean mysqlDriverPresent() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return true;
        }
        catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /** &sect;6.5-6: two holders of the same GET_LOCK are mutually exclusive; the loser times out. */
    @Test
    void lockIsMutuallyExclusiveAndTimeoutFailsWithTk2002() throws Exception {
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicReference<String> holderResult = new AtomicReference<>();
        Thread holder = new Thread(() -> holderResult.set(lockManager.withTenantLock(TENANT, Duration.ofSeconds(30),
                () -> {
                    holderAcquired.countDown();
                    try {
                        releaseHolder.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "held";
                })));
        holder.start();
        assertThat(holderAcquired.await(30, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> provisioner.provision(TENANT, SeedProfile.of("basic"),
                    new ProvisionOptions(null, Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(TenantProvisioningException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("TK-2002");
                        assertThat(ex.getMessage()).contains("lock");
                    });
        }
        finally {
            releaseHolder.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(holderResult.get()).isEqualTo("held");
    }

    /** ADR-0001 layer 3: a failed provisioning run must not leak the session lock. */
    @Test
    void failedRunDoesNotLeakTheLock() {
        jdbc.update("DELETE FROM tenantkit_seed_history WHERE tenant_id = ?", TENANT.value());
        jdbc.update("DELETE FROM tenant_notes WHERE tenant_id = ?", TENANT.value());

        assertThatThrownBy(() -> provisioner.provision(TENANT, SeedProfile.of("broken"),
                ProvisionOptions.defaults())).isInstanceOf(io.tenantkit.provisioning.ProvisionFailedException.class);

        // the lock is free again: a full run acquires and completes
        ProvisionReport recovered = provisioner.provision(TENANT, SeedProfile.of("basic"),
                ProvisionOptions.defaults());
        assertThat(recovered.succeeded()).isTrue();
        assertThat(recovered.executed()).hasSize(2);
    }

    /** Two instances racing on one tenant: exactly-once execution, idempotent both sides. */
    @Test
    void concurrentProvisionsOfOneTenantExecuteExactlyOnce() throws Exception {
        jdbc.update("DELETE FROM tenantkit_seed_history WHERE tenant_id = ?", TENANT.value());
        jdbc.update("DELETE FROM tenant_notes WHERE tenant_id = ?", TENANT.value());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProvisionReport>> results = pool.invokeAll(List.of(
                    () -> provisioner.provision(TENANT, SeedProfile.of("basic"), ProvisionOptions.defaults()),
                    () -> provisioner.provision(TENANT, SeedProfile.of("basic"), ProvisionOptions.defaults())));
            ProvisionReport first = results.get(0).get(60, TimeUnit.SECONDS);
            ProvisionReport second = results.get(1).get(60, TimeUnit.SECONDS);

            assertThat(first.succeeded()).isTrue();
            assertThat(second.succeeded()).isTrue();
            // which future wins the lock race is scheduling-dependent: assert the
            // split, not the identity of the winner (a real first-run flake)
            assertThat(java.util.List.of(first.executed().size(), second.executed().size()))
                    .as("exactly one run executes both scripts").containsExactlyInAnyOrder(2, 0);
            assertThat(java.util.List.of(first.skipped().size(), second.skipped().size()))
                    .as("the other run skips both scripts").containsExactlyInAnyOrder(2, 0);
            Integer ledgerRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tenantkit_seed_history WHERE tenant_id = ?", Integer.class, TENANT.value());
            assertThat(ledgerRows).isEqualTo(2);
            Integer notes = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_notes WHERE tenant_id = ?",
                    Integer.class, TENANT.value());
            assertThat(notes).isEqualTo(2);
        }
        finally {
            pool.shutdownNow();
        }
    }

    /**
     * R4 backlog #11: the lock must remain session-bound when connections come from a
     * real pool. Hikari hands out a proxy per checkout; GET_LOCK is issued on the proxy's
     * single physical session, so a second checkout (different physical connection) must
     * NOT see the lock, while the holder still owns it.
     */
    @Test
    void lockIsSessionBoundThroughAHikariPool() throws Exception {
        var mysql = TenantSupport.mysql();
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl());
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setMaximumPoolSize(4);
        config.setPoolName("tenantkit-lock-proof");
        try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(pool);
            io.tenantkit.provisioning.TenantLockManager lockManager = new io.tenantkit.provisioning.MysqlGetLockManager(jdbc);

            java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            Thread holder = new Thread(() -> lockManager.withTenantLock(TenantId.of("hikari-proof"),
                    Duration.ofSeconds(30), () -> {
                        acquired.countDown();
                        try {
                            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }));
            holder.start();
            assertThat(acquired.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // a DIFFERENT pooled connection must fail to acquire the same lock
            assertThatThrownBy(() -> lockManager.withTenantLock(TenantId.of("hikari-proof"),
                    Duration.ofSeconds(1), () -> null))
                    .isInstanceOfSatisfying(TenantProvisioningException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo("TK-2002"));

            // and the holder still owns it (session binding survived pooling)
            Integer owned = jdbc.queryForObject(
                    "SELECT IS_USED_LOCK(?) IS NOT NULL", Integer.class,
                    "tenantkit:provision:hikari-proof"); // documented lock-name format
            assertThat(owned).isEqualTo(1);

            release.countDown();
            holder.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(30));
        }
        finally {
            org.springframework.jdbc.core.JdbcTemplate cleanup =
                    new org.springframework.jdbc.core.JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            cleanup.execute("SELECT RELEASE_LOCK('tenantkit:provision:hikari-proof')");
        }
    }
}
