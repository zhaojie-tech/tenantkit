package io.tenantkit.starter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantKitExecutorPropertiesTest {

    @Test
    void defaultsOffAndSettable() {
        TenantKitExecutorProperties properties = new TenantKitExecutorProperties();
        assertThat(properties.isDecorateUserPools()).isFalse();
        properties.setDecorateUserPools(true);
        assertThat(properties.isDecorateUserPools()).isTrue();
    }
}
