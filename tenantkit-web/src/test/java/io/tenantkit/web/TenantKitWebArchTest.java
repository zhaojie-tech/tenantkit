package io.tenantkit.web;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Web architecture gate (DESIGN §6.2/§8): the kit never parses unverified
 * JWTs — the jwt-claim strategy reads already-verified claims from Spring
 * Security's authentication only.
 */
@AnalyzeClasses(packages = "io.tenantkit", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantKitWebArchTest {

    /** No raw JWT parsing library may enter the kit (JJWT or a hand-rolled decoder). */
    @ArchTest
    static final ArchRule noRawJwtParseLibraries = noClasses()
            .that().resideInAPackage("io.tenantkit..")
            .should().dependOnClassesThat().resideInAnyPackage("io.jsonwebtoken..")
            .because("TenantKit refuses to parse unverified tokens; trust lives in SecurityContext only");

    /** Only the isolated claim reader may touch Jwt types, and never a decoder/verifier. */
    @ArchTest
    static final ArchRule noJwtVerificationInsideKit = noClasses()
            .that().resideInAPackage("io.tenantkit..")
            .should().dependOnClassesThat().haveNameMatching(".*JwtDecoder|.*JwtVerifier|.*NimbusJwt.*")
            .because("verification belongs to the resource server before TenantKit ever sees a claim");

    /** Filter layering: web depends on core, never the other way. */
    @ArchTest
    static final ArchRule coreIsNotAwareOfWeb = noClasses()
            .that().resideInAPackage("io.tenantkit.core..")
            .should().dependOnClassesThat().resideInAPackage("io.tenantkit.web..");
}
