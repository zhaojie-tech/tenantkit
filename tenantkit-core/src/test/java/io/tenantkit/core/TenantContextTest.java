package io.tenantkit.core;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.tenantkit.core.exception.TenantMissingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TenantContext contract: fail-fast, structured scoping, nested restore, no implicit inheritance. */
class TenantContextTest {

    @Test
    void requiredFailsFastWithoutTenant() {
        assertThatThrownBy(TenantContext::required).isInstanceOf(TenantMissingException.class);
    }

    @Test
    void currentOrNullReturnsNullWithoutTenant() {
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void runAsBindsInsideAndRestoresOutside() {
        TenantId alpha = TenantId.of("alpha");
        TenantContext.runAs(alpha, () -> assertThat(TenantContext.required()).isSameAs(alpha));
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void callAsBindsInsideAndReturnsValue() throws Exception {
        TenantId beta = TenantId.of("beta");
        String result = TenantContext.callAs(beta, () -> TenantContext.required().value() + "!");
        assertThat(result).isEqualTo("beta!");
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void nestedRunAsRestoresOuterValueInsteadOfClearing() {
        TenantId outer = TenantId.of("outer");
        TenantId inner = TenantId.of("inner");
        TenantContext.runAs(outer, () -> {
            TenantContext.runAs(inner, () -> assertThat(TenantContext.required()).isSameAs(inner));
            assertThat(TenantContext.required()).as("outer value must be restored, not cleared")
                    .isSameAs(outer);
        });
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void actionExceptionStillRestoresContext() {
        TenantId alpha = TenantId.of("alpha");
        assertThatThrownBy(() -> TenantContext.runAs(alpha, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void callAsExceptionStillRestoresContext() {
        TenantId alpha = TenantId.of("alpha");
        Callable<Object> failing = () -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> TenantContext.callAs(alpha, failing)).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void rejectsNullArguments() {
        assertThatCode(() -> TenantContext.runAs(null, () -> { })).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> TenantContext.runAs(TenantId.of("a"), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void noImplicitInheritanceToNewThreads() throws Exception {
        TenantId alpha = TenantId.of("alpha");
        AtomicReference<TenantId> seenInChild = new AtomicReference<>();
        AtomicBoolean joinDone = new AtomicBoolean(false);
        TenantContext.runAs(alpha, () -> {
            Thread child = new Thread(() -> {
                seenInChild.set(TenantContext.currentOrNull());
                joinDone.set(true);
            });
            child.start();
            try {
                child.join(5000);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(joinDone).isTrue();
        assertThat(seenInChild.get()).as("plain ThreadLocal must not leak to new threads").isNull();
    }
}
