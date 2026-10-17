package io.tenantkit.test;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Matrix K05 from docs/matrix/assembly-matrix.md (frozen): the container
 * factory is lazy (no Docker contact before an explicit accessor call) and
 * singleton by construction. Container START is proven by the slow-tagged
 * smoke test, runnable with {@code mvn test -Pcontainers} on a Docker host.
 */
class TenantSupportTest {

    /** K05 — laziness: touching the factory never starts a container. */
    @Test
    void k05_factoryIsLazyAndDoesNotTouchDocker() throws Exception {
        assertThatCode(() -> Class.forName("io.tenantkit.test.TenantSupport")).doesNotThrowAnyException();
        assertThat(field("mysql")).isNull();
        assertThat(field("rabbitmq")).isNull();
        assertThat(field("redis")).isNull();
    }

    private static Object field(String name) throws Exception {
        Field field = TenantSupport.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Slow layer: real container start + singleton identity (requires Docker).
     * Redis only, deliberately (R4 P2): mysql/rabbitmq singletons are proven by
     * their own module suites, and starting all three here doubled the number of
     * container starts in the 20-minute CI budget.
     */
    @Test
    @org.junit.jupiter.api.Tag("slow")
    void containersAreSingletonsAcrossAccessors() {
        GenericContainer<?> first = TenantSupport.redis();
        GenericContainer<?> second = TenantSupport.redis();
        assertThat(second).isSameAs(first);
        assertThat(first.isRunning()).isTrue();
    }
}
