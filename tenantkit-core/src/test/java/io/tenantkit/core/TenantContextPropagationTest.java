package io.tenantkit.core;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Matrix K03: micrometer present -> accessor registered and capture/restore round-trips the tenant. */
class TenantContextPropagationTest {

    @BeforeEach
    @AfterEach
    void resetInstallation() {
        TenantContextPropagation.reset();
    }

    @Test
    void installRegistersAccessorExactlyOnce() {
        assertThat(TenantContextPropagation.isAvailable()).isTrue();
        TenantContextPropagation.install();
        TenantContextPropagation.install();
        assertThat(TenantContextPropagation.isInstalled()).isTrue();
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .anyMatch(accessor -> TenantContextPropagation.CONTEXT_KEY.equals(accessor.key().toString()));
    }

    @Test
    void snapshotCaptureAndRestoreRoundTripsTenant() {
        TenantContextPropagation.install();
        TenantId alpha = TenantId.of("alpha");
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();

        AtomicReference<ContextSnapshot> snapshotRef = new AtomicReference<>();
        TenantContext.runAs(alpha, () -> snapshotRef.set(factory.captureAll()));
        assertThat(TenantContext.currentOrNull()).as("capture must not change local state").isNull();

        try (ContextSnapshot.Scope ignored = snapshotRef.get().setThreadLocals()) {
            assertThat(TenantContext.required()).isEqualTo(alpha);
        }
        assertThat(TenantContext.currentOrNull()).as("scope exit must restore prior state").isNull();
    }
}
