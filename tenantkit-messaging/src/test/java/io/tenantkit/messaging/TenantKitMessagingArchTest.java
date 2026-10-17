package io.tenantkit.messaging;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Messaging architecture gate (DESIGN &sect;5): module dependencies stay
 * one-directional (starter &rarr; feature modules &rarr; core); messaging
 * never reaches into web or cache-redis, and core never learns about it.
 */
@AnalyzeClasses(packages = "io.tenantkit", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantKitMessagingArchTest {

    /** messaging must not depend on the web module. */
    @ArchTest
    static final ArchRule messagingIsNotAwareOfWeb = noClasses()
            .that().resideInAPackage("io.tenantkit..")
            .should().dependOnClassesThat().resideInAPackage("io.tenantkit.web..")
            .because("modules compose only through core; cross-module coupling would break the starter's optional assembly");

    /** messaging must not depend on the cache-redis module. */
    @ArchTest
    static final ArchRule messagingIsNotAwareOfRedis = noClasses()
            .that().resideInAPackage("io.tenantkit..")
            .should().dependOnClassesThat().resideInAnyPackage("io.tenantkit.cache..", "io.tenantkit.redis..")
            .because("modules compose only through core; cross-module coupling would break the starter's optional assembly");

    /** core stays a pure library: no upward reference to messaging. */
    @ArchTest
    static final ArchRule coreIsNotAwareOfMessaging = noClasses()
            .that().resideInAPackage("io.tenantkit.core..")
            .should().dependOnClassesThat().resideInAPackage("io.tenantkit.messaging..");

    /** The advice must not statically reference micrometer (observability stays opt-in). */
    @ArchTest
    static final ArchRule noMicrometerInAdvice = noClasses()
            .that().resideInAPackage("io.tenantkit.messaging..")
            .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
            .because("micrometer is optional; the messaging advice restores context+MDC manually only");
}
