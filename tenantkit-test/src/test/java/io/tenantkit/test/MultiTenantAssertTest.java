package io.tenantkit.test;

import java.util.HashMap;
import java.util.Map;

import io.tenantkit.core.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MultiTenantAssert contract (DESIGN §6.7). */
class MultiTenantAssertTest {

    @Test
    void passesWhenEachTenantSeesItsOwnValue() {
        Map<String, String> data = Map.of("alpha", "a-data", "beta", "b-data");
        assertThatCode(() -> MultiTenantAssert.assertThat(() -> data.get(TenantContext.required().value()))
                .forTenant("alpha").returns("a-data")
                .forTenant("beta").returns("b-data")).doesNotThrowAnyException();
    }

    @Test
    void failureNamesBothTenants() {
        // every tenant sees the same row: the classic leak this helper exists to catch
        Map<String, String> leaked = new HashMap<>(Map.of("alpha", "shared"));
        assertThatThrownBy(() -> MultiTenantAssert.assertThat(() -> leaked.get("alpha"))
                .forTenant("alpha").returns("shared")
                .forTenant("beta").returns("beta-own"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("beta")
                .hasMessageContaining("isolation");
    }

    @Test
    void satisfiesAllowsCustomRequirements() {
        MultiTenantAssert.assertThat(() -> TenantContext.required().value())
                .forTenant("alpha").satisfies(v -> assertThat(v).isEqualTo("alpha"));
    }

    @Test
    void invalidTenantNameIsRejectedUpFront() {
        assertThatThrownBy(() -> MultiTenantAssert.assertThat(() -> "x").forTenant("ALPHA").returns("x"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void leavesNoContextBehind() {
        MultiTenantAssert.assertThat(() -> TenantContext.currentOrNull())
                .forTenant("alpha").satisfies(v -> assertThat(v).isNotNull())
                .forTenant("beta").satisfies(v -> assertThat(v).isNotNull());
        assertThat(TenantContext.currentOrNull()).isNull();
    }
}
