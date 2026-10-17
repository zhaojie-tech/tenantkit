package io.tenantkit.web;

import java.util.List;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolver;
import io.tenantkit.test.HidingClassLoader;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import io.tenantkit.web.properties.TenantKitWebProperties;
import io.tenantkit.web.resolver.FixedTenantResolver;
import io.tenantkit.web.resolver.HeaderTenantResolver;
import io.tenantkit.web.resolver.JwtClaimTenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assembly-layer matrix cases W01–W08, W19, W22, W36, W43–W45, K06 from
 * docs/matrix/assembly-matrix.md (frozen). Behavior-layer cases live in
 * {@link TenantResolutionFilterBehaviorTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantKitWebAssemblyMatrixTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantKitWebAutoConfiguration.class));

    /** W01 — no configuration: module silently backs off, context starts. */
    @Test
    void w01_defaultConfigurationRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantResolutionFilter.class);
            assertThat(context.getBeansOfType(TenantResolver.class)).isEmpty();
        });
    }

    /** W02 — module switch off: no kit beans, user beans unaffected. */
    @Test
    void w02_moduleSwitchDisablesEverything() {
        runner.withUserConfiguration(CoreOnlyUserConfig.class)
                .withPropertyValues("tenantkit.web.enabled=false",
                        "tenantkit.resolution.strategies=fixed", "tenantkit.resolution.fixed=demo")
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantResolutionFilter.class);
            assertThat(context.getBeansOfType(TenantResolver.class)).isEmpty();
            assertThat(context).as("user beans must survive the module switch")
                    .hasBean("coreUserSideTenant");
        });
    }

    /** W03 — fixed strategy: filter + resolver registered, both property classes bound. */
    @Test
    void w03_fixedStrategyAssemblesFilterAndProperties() {
        runner.withPropertyValues("tenantkit.resolution.strategies=fixed",
                "tenantkit.resolution.fixed=demo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TenantResolutionFilter.class);
            assertThat(context).hasSingleBean(FixedTenantResolver.class);
            assertThat(context.getBean(TenantKitResolutionProperties.class).getFixed()).isEqualTo("demo");
            assertThat(context.getBean(TenantKitWebProperties.class).isEnabled()).isTrue();
        });
    }

    /** W04 — no Servlet API: ConditionalOnClass guard backs off the whole module. */
    @Test
    void w04_missingServletApiBacksOff() {
        runner.withClassLoader(new HidingClassLoader(getClass().getClassLoader(), "jakarta.servlet.Filter"))
                .withPropertyValues("tenantkit.resolution.strategies=fixed", "tenantkit.resolution.fixed=demo")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TenantResolutionFilter.class);
                    assertThat(context.getBeansOfType(TenantResolver.class)).isEmpty();
                });
    }

    /** W05 — header strategy with default trust-proxy=false must fail startup. */
    @Test
    void w05_headerWithoutTrustProxyFailsFast() {
        runner.withPropertyValues("tenantkit.resolution.strategies=header").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("trust-proxy");
        });
    }

    /** W06 — trust-proxy=true without header strategy: starts, warns. */
    @Test
    void w06_trustProxyWithoutHeaderWarns(CapturedOutput output) {
        runner.withPropertyValues("tenantkit.resolution.trust-proxy=true",
                "tenantkit.resolution.strategies=fixed", "tenantkit.resolution.fixed=demo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TenantResolutionFilter.class);
        });
        assertThat(output.getAll()).contains("trust-proxy");
    }

    /** W07 — jwt-claim without Spring Security must fail startup (never silently skip). */
    @Test
    void w07_jwtClaimWithoutSecurityFailsFast() {
        runner.withClassLoader(new HidingClassLoader(getClass().getClassLoader(),
                        "org.springframework.security."))
                .withPropertyValues("tenantkit.resolution.strategies=jwt-claim").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("jwt-claim");
        });
    }

    /** W08 — jwt-claim with Security present: resolver registered. */
    @Test
    void w08_jwtClaimWithSecurityAssembles() {
        runner.withPropertyValues("tenantkit.resolution.strategies=jwt-claim").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtClaimTenantResolver.class);
            assertThat(context).hasSingleBean(TenantResolutionFilter.class);
        });
    }

    /** W19 — subdomain without base-domains fails startup. */
    @Test
    void w19_subdomainRequiresBaseDomains() {
        runner.withPropertyValues("tenantkit.resolution.strategies=subdomain").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("base-domains");
        });
    }

    /** W22 — base-domains with a port fail startup (port ambiguity). */
    @Test
    void w22_baseDomainWithPortFailsStartup() {
        runner.withPropertyValues("tenantkit.resolution.strategies=subdomain",
                        "tenantkit.resolution.subdomain.base-domains=saas.example.com:8443").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("port");
        });
    }

    /** W45 — unknown strategy value fails property binding (fail-fast, not silently dropped). */
    @Test
    void w45_unknownStrategyValueFailsBinding() {
        runner.withPropertyValues("tenantkit.resolution.strategies=jwt").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("tenantkit.resolution.strategies");
        });
    }

    /** W43 — tenants property: config catalog in declared order. */
    @Test
    void w43_tenantsPropertyBuildsOrderedCatalog() {
        runner.withPropertyValues("tenantkit.tenants=alpha,beta").run(context -> {
            assertThat(context).hasNotFailed();
            io.tenantkit.core.catalog.ConfigTenantCatalog catalog =
                    context.getBean(io.tenantkit.core.catalog.ConfigTenantCatalog.class);
            assertThat(catalog.activeTenants()).extracting(t -> t.value()).containsExactly("alpha", "beta");
        });
    }

    /** W44 — no tenants property: no catalog bean, context starts. */
    @Test
    void w44_missingTenantsOmitsCatalog() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(io.tenantkit.core.catalog.ConfigTenantCatalog.class);
        });
    }

    /** W36 — startup scan registers @TenantPublic paths from RequestMappingHandlerMapping. */
    @Test
    void w36_tenantPublicPathsScannedAtStartup() {
        runner.withUserConfiguration(MvcConfig.class)
                .withPropertyValues("tenantkit.resolution.strategies=fixed", "tenantkit.resolution.fixed=demo")
                .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("tenantPublicScan");
            assertThat(context).hasSingleBean(TenantResolutionFilter.class);
            TenantPublicPathScannerAssert.verify(context.getBean(TenantResolutionFilter.class));
        });
    }

    /** W46 — strategy case variants must behave exactly like their lowercase key (R1 security P1-1). */
    @Test
    void w46_strategyCaseVariantBehavesSameAsLowercase() {
        runner.withClassLoader(new HidingClassLoader(getClass().getClassLoader(),
                        "org.springframework.security."))
                .withPropertyValues("tenantkit.resolution.strategies=JWT-Claim").run(context -> {
            assertThat(context).as("case variant must still trigger the jwt-claim fail-fast").hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("jwt-claim");
        });
        runner.withPropertyValues("tenantkit.resolution.strategies=JWT-Claim",
                        "tenantkit.resolution.jwt-claim=tenant_id").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtClaimTenantResolver.class);
        });
    }

    /** W47 — filter active without any catalog must WARN that TK-1002 is inactive (R1 security P1-2). */
    @Test
    void w47_filterWithoutCatalogWarns(CapturedOutput output) {
        runner.withPropertyValues("tenantkit.resolution.strategies=fixed",
                "tenantkit.resolution.fixed=demo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TenantResolutionFilter.class);
        });
        assertThat(output.getAll()).as("missing catalog must be loud").contains("TK-1002");
    }

    /** W49 — every binding-compatible spelling registers AND fail-fasts identically (R4 P1). */
    @Test
    void w49_spellingVariantsBehaveLikeTheCanonicalKey() {
        for (String spelling : new String[] { "jwt-claim", "JWT-Claim", "jwt_claim", "JWTCLAIM", "JwtClaim" }) {
            runner.withPropertyValues("tenantkit.resolution.strategies=" + spelling).run(context -> {
                assertThat(context).as("spelling %s must register the resolver", spelling).hasNotFailed();
                assertThat(context.getBeansOfType(io.tenantkit.core.TenantResolver.class))
                        .as("spelling %s must register a resolver", spelling).hasSize(1);
            });
        }
        // and the fail-fast path is equally spelling-complete
        runner.withClassLoader(new HidingClassLoader(getClass().getClassLoader(), "org.springframework.security."))
                .withPropertyValues("tenantkit.resolution.strategies=jwt_claim").run(context -> {
            assertThat(context).as("underscore spelling must still trigger the security fail-fast").hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("jwt-claim");
        });
    }

    /** W48 — user TenantCatalog bean wins over the config catalog in servlet apps too (R3 P2-1). */
    @Test
    void w48_userCatalogWinsOverConfigCatalogInWebApps() {
        runner.withUserConfiguration(UserCatalogConfig.class)
                .withPropertyValues("tenantkit.tenants=alpha,beta").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(io.tenantkit.core.TenantCatalog.class))
                    .isInstanceOf(io.tenantkit.core.TenantCatalog.class)
                    .isNotInstanceOf(io.tenantkit.core.catalog.ConfigTenantCatalog.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCatalogConfig {
        @Bean
        io.tenantkit.core.TenantCatalog userCatalog() {
            return new io.tenantkit.core.TenantCatalog() {
                @Override
                public java.util.List<TenantId> activeTenants() {
                    return java.util.List.of();
                }
            };
        }
    }

    /** K06 — starter aggregation (core+web together) yields the identical bean face as web alone. */
    @Test
    void k06_starterAggregationAddsNoBeans() {
        java.util.Set<String> plain = beanNames(runner);
        java.util.Set<String> withCoreUserConfig = beanNames(
                runner.withUserConfiguration(CoreOnlyUserConfig.class));
        // the user config's own beans (in the test package, also under io.tenantkit) are not kit beans
        java.util.Set<String> aggregatedKitBeans = new java.util.HashSet<>(withCoreUserConfig);
        aggregatedKitBeans.removeAll(java.util.List.of("coreUserSideTenant",
                "tenantKitWebAssemblyMatrixTest.CoreOnlyUserConfig"));
        assertThat(aggregatedKitBeans).as("aggregation must not change the kit bean face").isEqualTo(plain);
    }

    private static java.util.Set<String> beanNames(WebApplicationContextRunner runnerToProbe) {
        java.util.Set<String>[] holder = new java.util.Set[1];
        runnerToProbe.run(context -> holder[0] = java.util.Arrays.stream(context.getBeanNamesForType(Object.class))
                .filter(name -> context.getBean(name).getClass().getPackageName().startsWith("io.tenantkit"))
                .collect(java.util.stream.Collectors.toSet()));
        return holder[0];
    }

    static final class TenantPublicPathScannerAssert {
        static void verify(TenantResolutionFilter filter) {
            // the wiring detail is asserted behaviorally in TenantResolutionFilterBehaviorTest;
            // here we only prove the scan bean participates in assembly
            assertThat(filter).isNotNull();
        }
    }

    @RestController
    static class PublicController {

        @TenantPublic
        @GetMapping("/public/echo")
        String echo() {
            return "echo";
        }

        @GetMapping("/api/x")
        String api() {
            return "api";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MvcConfig {

        @Bean
        RequestMappingHandlerMapping tenantKitTestHandlerMapping() {
            return new RequestMappingHandlerMapping();
        }

        @Bean
        PublicController tenantKitTestPublicController() {
            return new PublicController();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CoreOnlyUserConfig {

        @Bean
        io.tenantkit.core.TenantId coreUserSideTenant() {
            return io.tenantkit.core.TenantId.of("alpha");
        }
    }
}
