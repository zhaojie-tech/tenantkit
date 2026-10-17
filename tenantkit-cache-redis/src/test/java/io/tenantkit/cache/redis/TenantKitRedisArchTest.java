package io.tenantkit.cache.redis;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture gate for the cache-redis module (DESIGN &sect;5: dependency
 * direction is one-way, starter &rarr; feature modules &rarr; core).
 */
@AnalyzeClasses(packages = "io.tenantkit", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantKitRedisArchTest {

    /** cache-redis depends on core only — never on sibling feature modules. */
    @ArchTest
    static final ArchRule redisDependsOnlyOnCore = noClasses()
            .that().resideInAPackage("io.tenantkit.cache.redis..")
            .should().dependOnClassesThat().resideInAnyPackage("io.tenantkit.web..",
                    "io.tenantkit.messaging..", "io.tenantkit.provisioning..")
            .because("feature modules must not couple to each other (DESIGN §5)");

    /** core stays unaware of the cache module. */
    @ArchTest
    static final ArchRule coreIsNotAwareOfCacheRedis = noClasses()
            .that().resideInAPackage("io.tenantkit.core..")
            .should().dependOnClassesThat().resideInAPackage("io.tenantkit.cache.redis..");
}
