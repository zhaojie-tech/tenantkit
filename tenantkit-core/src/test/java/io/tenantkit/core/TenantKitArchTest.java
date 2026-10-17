package io.tenantkit.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture gate (DESIGN §8): TenantContext carries no public mutators;
 * kit internals never bypass runAs/callAs. ArchUnit cannot police downstream
 * user code — the demo-app joins this gate from M3.
 */
@AnalyzeClasses(packages = "io.tenantkit", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantKitArchTest {

    /** Public static surface of TenantContext is exactly the designed four methods. */
    @ArchTest
    static final ArchRule tenantContextPublicSurface = methods().that().arePublic().and().areStatic()
            .and().areDeclaredIn(TenantContext.class)
            .should().haveNameMatching("required|currentOrNull|runAs|callAs")
            .because("TenantContext is explicitly scoped; public setters/clearers are forbidden (DESIGN §6.1/§8)");

    /** Only the propagation accessor bridge may use the package-private unsafeSet hook. */
    @ArchTest
    static final ArchRule unsafeSetOnlyFromAccessorBridge = noClasses()
            .that().doNotHaveFullyQualifiedName(TenantContext.class.getName())
            .and().doNotHaveFullyQualifiedName("io.tenantkit.core.TenantKitThreadLocalAccessor")
            .should().callMethod(TenantContext.class, "unsafeSet", TenantId.class)
            .because("binding outside runAs/callAs or the propagation bridge bypasses the scope contract");

    /** Kit internals never touch ThreadLocal/InheritableThreadLocal directly outside TenantContext. */
    @ArchTest
    static final ArchRule noDirectThreadLocalOutsideTenantContext = noClasses()
            .that().resideInAPackage("io.tenantkit..")
            .and().doNotHaveFullyQualifiedName(TenantContext.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.InheritableThreadLocal")
            .because("implicit inheritance and ad-hoc thread locals are forbidden; propagation is explicit");
}
