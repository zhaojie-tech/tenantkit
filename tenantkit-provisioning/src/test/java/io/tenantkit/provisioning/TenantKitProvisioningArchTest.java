package io.tenantkit.provisioning;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Provisioning architecture gate (DESIGN &sect;5 single-direction dependency
 * rule): provisioning may depend on core and Spring, never on the other kit
 * feature modules or their transports.
 */
@AnalyzeClasses(packages = "io.tenantkit", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantKitProvisioningArchTest {

    /** No sibling kit feature module may leak into provisioning. */
    @ArchTest
    static final ArchRule noSiblingKitModuleDependencies = noClasses()
            .that().resideInAPackage("io.tenantkit.provisioning..")
            .should().dependOnClassesThat().resideInAnyPackage("io.tenantkit.web..", "io.tenantkit.messaging..",
                    "io.tenantkit.cache.redis..")
            .because("starter aggregates modules; provisioning stays usable on its own (web/messaging/redis free)");

    /** No messaging transport or Redis client may enter provisioning. */
    @ArchTest
    static final ArchRule noTransportDependencies = noClasses()
            .that().resideInAPackage("io.tenantkit.provisioning..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..",
                    "org.springframework.data.redis..")
            .because("provisioning speaks JDBC and advisory locks only");

    /** Core stays unaware of provisioning (dependency direction: provisioning -> core). */
    @ArchTest
    static final ArchRule coreIsNotAwareOfProvisioning = noClasses()
            .that().resideInAPackage("io.tenantkit.core..")
            .should().dependOnClassesThat().resideInAPackage("io.tenantkit.provisioning..");
}
