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
package io.tenantkit.starter;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContextPropagation;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.foreach.Tenants;
import io.tenantkit.core.catalog.ConfigTenantCatalog;
import io.tenantkit.web.TenantKitWebAutoConfiguration;
import io.tenantkit.web.condition.OnTenantsConfiguredCondition;

/**
 * Ambient assembly for **non-web** applications (scheduled batches are the
 * primary {@code Tenants.forEach} audience): registers the config catalog and
 * wires the ambient catalog + propagation bridge. The web module does the same
 * in servlet apps; here it is the fallback for
 * {@code web.enabled=false} and non-servlet contexts, so {@code tenantkit.tenants}
 * never silently does nothing (R1 architecture review P1-9).
 */
@AutoConfiguration(after = TenantKitWebAutoConfiguration.class)
@org.springframework.context.annotation.Conditional(OnTenantsConfiguredCondition.class)
public class TenantKitAmbientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TenantCatalog.class)
    ConfigTenantCatalog ambientConfigTenantCatalog(Environment environment) {
        List<String> tenants = Binder.get(environment)
                .bind("tenantkit.tenants", Bindable.listOf(String.class))
                .orElse(List.of());
        return new ConfigTenantCatalog(tenants.stream().map(TenantId::of).toList());
    }

    @Bean
    AmbientBootstrap ambientBootstrap(ObjectProvider<TenantCatalog> catalog) {
        return new AmbientBootstrap(catalog);
    }

    /** Idempotent twin of the web module's bootstrap — safe to run in both worlds. */
    static final class AmbientBootstrap
            implements org.springframework.beans.factory.SmartInitializingSingleton {

        private final ObjectProvider<TenantCatalog> catalog;

        AmbientBootstrap(ObjectProvider<TenantCatalog> catalog) {
            this.catalog = catalog;
        }

        @Override
        public void afterSingletonsInstantiated() {
            TenantCatalog available = catalog.getIfAvailable();
            if (available != null) {
                Tenants.useCatalog(available);
            }
            if (TenantContextPropagation.isAvailable()) {
                TenantContextPropagation.install();
            }
        }
    }
}
