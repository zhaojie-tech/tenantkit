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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.tenantkit.provisioning.properties.TenantKitProvisioningProperties;

/**
 * Provisioning module auto-configuration (DESIGN &sect;5 speed table / &sect;6.5).
 * Assembly guards mirror the frozen matrix: the module only exists when
 * spring-jdbc is on the classpath ({@code @ConditionalOnClass(JdbcTemplate)})
 * and {@code tenantkit.provisioning.enabled} has not turned it off.
 *
 * <p>Startup behavior: when a {@link JdbcTemplate} bean is present, the seed
 * ledger table is probed once after all singletons exist; a missing table logs
 * a WARN with the reference DDL (the table itself is migrated by the
 * application's own Flyway/Liquibase &mdash; TenantKit does not embed a
 * migration engine). Calling {@code provision} on the missing table then fails
 * with TK-2004 carrying the same guidance.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(name = "tenantkit.provisioning.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantKitProvisioningProperties.class)
public class TenantKitProvisioningAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenantKitProvisioningAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    SeedLedger tenantKitSeedLedger(JdbcTemplate jdbcTemplate, TenantKitProvisioningProperties properties) {
        return new SeedLedger(jdbcTemplate, properties.getLedgerTable());
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    SeedScriptLocator tenantKitSeedScriptLocator(TenantKitProvisioningProperties properties) {
        return new SeedScriptLocator(properties.getSeedLocation());
    }

    /**
     * The provisioner. Requires a {@link PlatformTransactionManager} (one
     * script = one transaction); fails with an actionable message instead of a
     * raw NoSuchBeanDefinitionException. The lock manager is pluggable:
     * a user bean wins, otherwise the MySQL {@code GET_LOCK} default is used
     * &mdash; on engines without {@code GET_LOCK} (e.g. H2) provide your own
     * {@link TenantLockManager} bean (see its javadoc for the contract).
     */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(TenantProvisioner.class)
    TenantProvisioner tenantProvisioner(JdbcTemplate jdbcTemplate, SeedLedger ledger, SeedScriptLocator locator,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<TenantLockManager> lockManager, TenantKitProvisioningProperties properties) {
        PlatformTransactionManager tx = transactionManager.getIfAvailable();
        if (tx == null) {
            throw new IllegalStateException(
                    "tenantkit-provisioning requires a PlatformTransactionManager bean (one script = one "
                            + "transaction); add spring-boot-starter-jdbc's DataSourceTransactionManager or "
                            + "define one (e.g. DataSourceTransactionManager on your DataSource)");
        }
        TenantLockManager lock = lockManager.getIfAvailable();
        if (lock == null) {
            lock = new MysqlGetLockManager(jdbcTemplate);
        }
        return new DefaultTenantProvisioner(locator, ledger, lock, new TransactionTemplate(tx), jdbcTemplate,
                Duration.ofSeconds(properties.getLockTimeoutSeconds()), properties.isDryRun());
    }

    /**
     * One-shot ledger existence probe at startup (ADR-0001 / DESIGN &sect;6.5):
     * missing table &rarr; WARN with the reference DDL, provisioning fails
     * later with TK-2004 and the same guidance.
     */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    SmartInitializingSingleton tenantKitSeedLedgerStartupProbe(SeedLedger ledger,
            TenantKitProvisioningProperties properties) {
        return () -> {
            if (!ledger.tableExists()) {
                log.warn("[tenantkit-provisioning] seed ledger table '{}' does not exist; provisioning will fail "
                        + "with TK-2004 until it is created by your own migration (Flyway/Liquibase). "
                        + "Reference DDL: {}", properties.getLedgerTable(), SeedLedger.REFERENCE_DDL);
            }
        };
    }
}
