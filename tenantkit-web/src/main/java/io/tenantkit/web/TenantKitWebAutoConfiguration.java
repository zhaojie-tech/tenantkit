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
package io.tenantkit.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.tenantkit.core.TenantCatalog;
import io.tenantkit.core.TenantContextPropagation;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.core.foreach.Tenants;
import io.tenantkit.core.catalog.ConfigTenantCatalog;
import io.tenantkit.web.condition.ConditionalOnStrategy;
import io.tenantkit.web.condition.OnTenantsConfiguredCondition;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import io.tenantkit.web.properties.TenantKitWebProperties;
import io.tenantkit.web.resolver.FixedTenantResolver;
import io.tenantkit.web.resolver.HeaderTenantResolver;
import io.tenantkit.web.resolver.JwtClaimTenantResolver;
import io.tenantkit.web.resolver.SubdomainTenantResolver;

/**
 * Web module auto-configuration (DESIGN &sect;5/§6.2). Composition rules that
 * would silently weaken the trust model fail startup instead (W05/W07/W19/W22);
 * the validation runs in a {@link BeanFactoryPostProcessor} so it precedes any
 * bean instantiation.
 */
@AutoConfiguration(afterName = { "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration" })
@ConditionalOnClass(jakarta.servlet.Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "tenantkit.web.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({ TenantKitWebProperties.class, TenantKitResolutionProperties.class })
public class TenantKitWebAutoConfiguration {

    /**
     * Trust-model validation before any bean is created: strategy/property
     * contradictions abort startup with an actionable message; safe defaults
     * never swallow an explicit user configuration.
     */
    @Bean
    static BeanFactoryPostProcessor tenantKitResolutionTrustModelValidator() {
        return beanFactory -> {
            Environment environment = beanFactory.getBean(Environment.class);
            ClassLoader beanClassLoader = beanFactory.getBeanClassLoader();
            Binder binder = Binder.get(environment);
            TenantKitResolutionProperties defaults = new TenantKitResolutionProperties();
            List<String> strategies = binder
                    .bind("tenantkit.resolution.strategies", Bindable.listOf(String.class))
                    .orElse(List.of());
            // the charset pattern is global TenantId semantics (catalog construction,
            // custom resolvers, forEach) — apply it even with no strategy configured
            String tenantIdPattern = binder.bind("tenantkit.resolution.tenant-id-pattern", String.class)
                    .orElse(defaults.getTenantIdPattern());
            TenantIdBridge.usePattern(tenantIdPattern);
            if (strategies.isEmpty()) {
                return;
            }
            boolean jwtClaim = hasStrategy(strategies, "jwt-claim");
            boolean header = hasStrategy(strategies, "header");
            boolean subdomain = hasStrategy(strategies, "subdomain");
            boolean fixed = hasStrategy(strategies, "fixed");

            if (fixed && !org.springframework.util.StringUtils.hasText(
                    binder.bind("tenantkit.resolution.fixed", String.class).orElse(null))) {
                throw new IllegalStateException(
                        "tenantkit.resolution.strategies contains 'fixed' but tenantkit.resolution.fixed is not set; "
                                + "declare the fixed tenant value or remove the strategy");
            }
            if (jwtClaim && !TenantIdBridge.securityPresent(beanClassLoader)) {
                throw new IllegalStateException(
                        "tenantkit.resolution.strategies contains 'jwt-claim' but Spring Security is not on the classpath. "
                                + "TenantKit refuses to resolve tenants from unverified tokens; add spring-security-oauth2-resource-server "
                                + "or drop the jwt-claim strategy (never parse raw Authorization headers)");
            }
            boolean trustProxy = binder.bind("tenantkit.resolution.trust-proxy", Boolean.class)
                    .orElse(defaults.isTrustProxy());
            if (header && !trustProxy) {
                throw new IllegalStateException(
                        "tenantkit.resolution.strategies contains 'header' but tenantkit.resolution.trust-proxy=false. "
                                + "A directly connected client can forge the tenant header; set trust-proxy=true only behind a "
                                + "boundary that strips inbound X-Tenant-Id headers, or remove the header strategy");
            }
            if (trustProxy && !header) {
                // warn, not fail: harmless over-trust flag with no consumer
                org.slf4j.LoggerFactory.getLogger(TenantKitWebAutoConfiguration.class)
                        .warn("tenantkit.resolution.trust-proxy=true but no 'header' strategy is configured; "
                                + "the flag has no effect");
            }
            if (subdomain) {
                List<String> baseDomains = binder
                        .bind("tenantkit.resolution.subdomain.base-domains", Bindable.listOf(String.class))
                        .orElse(List.of());
                if (baseDomains.isEmpty()) {
                    throw new IllegalStateException(
                            "tenantkit.resolution.strategies contains 'subdomain' but tenantkit.resolution.subdomain.base-domains is empty; "
                                    + "sub-domain matching requires at least one base domain such as saas.example.com");
                }
                if (baseDomains.stream().anyMatch(domain -> domain.contains(":"))) {
                    throw new IllegalStateException(
                            "tenantkit.resolution.subdomain.base-domains must not contain ports (ambiguous trust boundary); "
                                    + "declare bare host names, the resolver matches any port");
                }
            }
        };
    }

    private static boolean hasStrategy(List<String> configured, String key) {
        // Boot's lenient enum converter keeps only letters/digits and lowercases before
        // matching, so "JWT-Claim", "jwt_claim" and "jwtclaim" all bind to JWT_CLAIM.
        // Guards must normalize identically or those spellings bind fine while every
        // fail-fast and the resolver registration are silently skipped (R4 P1).
        String canonicalKey = io.tenantkit.web.properties.TenantKitResolutionProperties
                .canonicalStrategy(key);
        for (String value : configured) {
            if (canonicalKey.equals(
                    io.tenantkit.web.properties.TenantKitResolutionProperties.canonicalStrategy(value))) {
                return true;
            }
        }
        return false;
    }

    /** Isolated bridge so validator verification never touches optional types. */
    private static final class TenantIdBridge {
        static void usePattern(String pattern) {
            io.tenantkit.core.TenantId.usePattern(pattern);
        }

        static boolean securityPresent(ClassLoader loader) {
            // probe the class the runtime actually touches (JwtClaimReader instanceof
            // JwtAuthenticationToken) — probing security-core alone let form-login apps
            // boot green and NCDFE on the first authenticated request
            try {
                Class.forName("org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
                        false, loader);
                return true;
            }
            catch (ClassNotFoundException ex) {
                return false;
            }
        }
    }

    @Bean
    @org.springframework.context.annotation.Conditional(OnTenantsConfiguredCondition.class)
    @ConditionalOnMissingBean(TenantCatalog.class)
    ConfigTenantCatalog configTenantCatalog(Environment environment) {
        List<String> tenants = Binder.get(environment)
                .bind("tenantkit.tenants", Bindable.listOf(String.class))
                .orElse(List.of());
        return new ConfigTenantCatalog(tenants.stream().map(io.tenantkit.core.TenantId::of).toList());
    }

    @Bean
    ResolutionBootstrap resolutionBootstrap(ObjectProvider<TenantCatalog> catalog, Environment environment) {
        return new ResolutionBootstrap(catalog, environment);
    }

    /** Wires the ambient catalog + propagation bridge once all singletons exist. */
    static final class ResolutionBootstrap implements org.springframework.beans.factory.SmartInitializingSingleton {

        private final ObjectProvider<TenantCatalog> catalog;
        private final Environment environment;

        ResolutionBootstrap(ObjectProvider<TenantCatalog> catalog, Environment environment) {
            this.catalog = catalog;
            this.environment = environment;
        }

        @Override
        public void afterSingletonsInstantiated() {
            TenantCatalog available = catalog.getIfAvailable();
            if (available != null) {
                if (!(available instanceof ConfigTenantCatalog)
                        && environment.containsProperty("tenantkit.tenants")) {
                    org.slf4j.LoggerFactory.getLogger(ResolutionBootstrap.class)
                            .warn("tenantkit.tenants is configured but a user TenantCatalog bean wins; "
                                    + "the property is ignored. Remove one of the two to avoid confusion.");
                }
                Tenants.useCatalog(available);
            }
            if (TenantContextPropagation.isAvailable()) {
                TenantContextPropagation.install();
            }
        }
    }

    @Bean
    @ConditionalOnStrategy("jwt-claim")
    JwtClaimTenantResolver jwtClaimTenantResolver(TenantKitResolutionProperties properties) {
        return new JwtClaimTenantResolver(properties.getJwtClaim(), properties.getStrategies()
                .indexOf(TenantKitResolutionProperties.Strategy.JWT_CLAIM) * 100);
    }

    @Bean
    @ConditionalOnStrategy("header")
    HeaderTenantResolver headerTenantResolver(TenantKitResolutionProperties properties) {
        return new HeaderTenantResolver(properties, properties.getStrategies()
                .indexOf(TenantKitResolutionProperties.Strategy.HEADER) * 100);
    }

    @Bean
    @ConditionalOnStrategy("subdomain")
    SubdomainTenantResolver subdomainTenantResolver(TenantKitResolutionProperties properties) {
        return new SubdomainTenantResolver(properties.getSubdomain().getBaseDomains(), properties.getStrategies()
                .indexOf(TenantKitResolutionProperties.Strategy.SUBDOMAIN) * 100);
    }

    @Bean
    @ConditionalOnStrategy("fixed")
    FixedTenantResolver fixedTenantResolver(TenantKitResolutionProperties properties) {
        return new FixedTenantResolver(properties, properties.getStrategies()
                .indexOf(TenantKitResolutionProperties.Strategy.FIXED) * 100);
    }

    @Bean
    @ConditionalOnBean(TenantResolver.class)
    @ConditionalOnMissingBean
    TenantResolutionFilter tenantResolutionFilter(List<TenantResolver> resolvers,
            ObjectProvider<TenantCatalog> catalog,
            ObjectProvider<TenantPublicScan> tenantPublicScan,
            TenantKitResolutionProperties properties) {
        List<TenantResolver> chain = new ArrayList<>(resolvers);
        chain.sort(Comparator.comparingInt(TenantResolver::order));
        TenantPublicScan scan = tenantPublicScan.getIfAvailable();
        TenantResolutionFilter.TenantPublicPaths publicPaths = scan == null ? null : scan.paths();
        io.tenantkit.core.TenantCatalog resolvedCatalog = catalog.getIfAvailable();
        if (resolvedCatalog == null) {
            org.slf4j.LoggerFactory.getLogger(TenantKitWebAutoConfiguration.class)
                    .warn("TenantResolutionFilter is active but no TenantCatalog is configured "
                            + "(tenantkit.tenants empty and no TenantCatalog bean): unknown-tenant "
                            + "validation (TK-1002) is INACTIVE and any resolved tenant is accepted. "
                            + "Configure tenantkit.tenants or register a TenantCatalog bean.");
        }
        return new TenantResolutionFilter(chain, properties, publicPaths, resolvedCatalog);
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean
    TenantPublicScan tenantPublicScan(RequestMappingHandlerMapping mapping) {
        return () -> TenantPublicPathScanner.scan(mapping);
    }

    @Bean
    @ConditionalOnMissingBean(TenantKitExceptionAdvice.class)
    TenantKitExceptionAdvice tenantKitExceptionAdvice(TenantKitResolutionProperties properties) {
        return new TenantKitExceptionAdvice(properties);
    }

    /** Lazy holder so the mapping scan happens once, when the filter is built. */
    interface TenantPublicScan {

        TenantResolutionFilter.TenantPublicPaths paths();
    }
}
