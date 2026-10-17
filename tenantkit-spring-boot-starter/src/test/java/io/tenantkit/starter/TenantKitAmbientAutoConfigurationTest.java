package io.tenantkit.starter;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.foreach.Tenants;
import io.tenantkit.core.catalog.ConfigTenantCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/** Matrix K09: non-web ambient assembly — tenantkit.tenants works without the servlet stack. */
class TenantKitAmbientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantKitAmbientAutoConfiguration.class));

    @AfterEach
    void clearAmbient() {
        Tenants.clearCatalog();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void k09_catalogRegistersWithoutServletStackAndFeedsForEach() {
        runner.withPropertyValues("tenantkit.tenants=alpha,beta").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConfigTenantCatalog.class);
            List<String> seen = new java.util.ArrayList<>();
            Tenants.forEach((id, tenant) -> seen.add(id.value()),
                    io.tenantkit.core.foreach.ForEachOptions.defaults());
            assertThat(seen).containsExactly("alpha", "beta");
        });
    }

    @Test
    void userCatalogBeanWinsOverConfigCatalog() {
        runner.withUserConfiguration(UserCatalogConfig.class)
                .withPropertyValues("tenantkit.tenants=alpha,beta").run(context -> {
            assertThat(context).hasSingleBean(TenantCatalog.class);
            assertThat(context.getBean(TenantCatalog.class)).isInstanceOf(UserCatalog.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCatalogConfig {
        @Bean
        TenantCatalog userCatalog() {
            return new UserCatalog();
        }
    }

    static class UserCatalog implements TenantCatalog {
        @Override
        public List<TenantId> activeTenants() {
            return List.of(TenantId.of("custom"));
        }
    }
}
