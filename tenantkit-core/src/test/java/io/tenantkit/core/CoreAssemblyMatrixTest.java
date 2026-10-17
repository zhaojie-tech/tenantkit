package io.tenantkit.core;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matrix K01/K02/K04 from docs/matrix/assembly-matrix.md (frozen).
 * K03 (micrometer present) lives in TenantContextPropagationTest.
 */
class CoreAssemblyMatrixTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    /** K01 — core alone: zero auto-configuration, pure library. */
    @Test
    void k01_coreRegistersNoBeans() {
        runner.withUserConfiguration(EmptyConfig.class).run(context -> {
            assertThat(context).isNotNull();
            Map<String, Object> kitBeans = context.getBeansOfType(Object.class);
            assertThat(kitBeans.keySet()).noneMatch(name -> name.toLowerCase().contains("tenant"));
        });
    }

    /** K02 — spring-tx absent: forEach still runs, detection silently off, no NoClassDefFoundError. */
    @Test
    void k02_forEachWorksWithSpringTxAbsent() throws Exception {
        Class<?> detector = Class.forName("io.tenantkit.core.foreach.SpringTxOuterTransactionDetector");
        Method isPresent = detector.getDeclaredMethod("isPresent", ClassLoader.class);
        isPresent.setAccessible(true);
        try (HidingClassLoader hidden = new HidingClassLoader(CoreAssemblyMatrixTest.class.getClassLoader(),
                "org.springframework.transaction.")) {
            assertThat(isPresent.invoke(null, hidden)).as("spring-tx must appear absent").isEqualTo(false);

            Class<?> tenantsClass = hidden.loadClass("io.tenantkit.core.foreach.Tenants");
            Class<?> catalogClass = hidden.loadClass("io.tenantkit.core.TenantCatalog");
            Class<?> optionsClass = hidden.loadClass("io.tenantkit.core.foreach.ForEachOptions");
            Class<?> idClass = hidden.loadClass("io.tenantkit.core.TenantId");

            Object catalog = java.lang.reflect.Proxy.newProxyInstance(hidden, new Class<?>[] { catalogClass },
                    (proxy, method, args) -> {
                        if (method.getName().equals("activeTenants")) {
                            Object alpha = idClass.getMethod("of", String.class).invoke(null, "alpha");
                            Object beta = idClass.getMethod("of", String.class).invoke(null, "beta");
                            return java.util.List.of(alpha, beta);
                        }
                        return defaultValue(method.getReturnType());
                    });
            Object options = optionsClass.getMethod("defaults").invoke(null);
            AtomicBoolean ran = new AtomicBoolean(false);
            Class<?> biConsumerClass = Class.forName("java.util.function.BiConsumer");
            Object action = java.lang.reflect.Proxy.newProxyInstance(hidden,
                    new Class<?>[] { biConsumerClass }, (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            ran.set(true);
                        }
                        return null;
                    });

            Method forEach = tenantsClass.getMethod("forEach", catalogClass, biConsumerClass, optionsClass);
            Object report = forEach.invoke(null, catalog, action, options);
            assertThat(ran).isTrue();
            assertThat(report.toString()).as("both tenants must have executed").contains("alpha", "beta");
        }
    }

    /** K04 — micrometer absent: no accessor, behavior unchanged, install rejects explicitly. */
    @Test
    void k04_propagationDegradesWithoutMicrometer() throws Exception {
        try (HidingClassLoader hidden = new HidingClassLoader(CoreAssemblyMatrixTest.class.getClassLoader(),
                "io.micrometer.")) {
            Class<?> propagation = hidden.loadClass("io.tenantkit.core.TenantContextPropagation");
            Method isAvailable = propagation.getMethod("isAvailable");
            assertThat((boolean) isAvailable.invoke(null)).as("micrometer must appear absent").isFalse();

            Method install = propagation.getMethod("install");
            try {
                install.invoke(null);
                throw new AssertionError("install must reject when context-propagation is absent");
            }
            catch (java.lang.reflect.InvocationTargetException ex) {
                assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("context-propagation");
            }

            // runAs/callAs behavior unchanged without micrometer
            Class<?> contextClass = hidden.loadClass("io.tenantkit.core.TenantContext");
            Class<?> idClass = hidden.loadClass("io.tenantkit.core.TenantId");
            Object alpha = idClass.getMethod("of", String.class).invoke(null, "alpha");
            Method runAs = contextClass.getMethod("runAs", idClass, Runnable.class);
            AtomicBoolean ran = new AtomicBoolean(false);
            runAs.invoke(null, alpha, (Runnable) () -> ran.set(true));
            assertThat(ran).isTrue();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {

        @Bean
        String marker() {
            return "marker";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AssertableHolder {
        // reference kept so the AssertableApplicationContext import is exercised in one place
        @SuppressWarnings("unused")
        Class<AssertableApplicationContext> type() {
            return AssertableApplicationContext.class;
        }
    }
}
