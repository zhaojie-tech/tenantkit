package io.tenantkit.core.catalog;

import java.util.ArrayList;
import java.util.List;

import io.tenantkit.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTenantCatalogTest {

    @Test
    void rejectsNullAndEmptyLists() {
        assertThatCode(() -> new ConfigTenantCatalog(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConfigTenantCatalog(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantkit.tenants");
    }

    @Test
    void catalogIsImmutableAgainstCallerMutation() {
        List<TenantId> mutable = new ArrayList<>();
        mutable.add(TenantId.of("alpha"));
        ConfigTenantCatalog catalog = new ConfigTenantCatalog(mutable);
        mutable.add(TenantId.of("beta"));
        assertThat(catalog.activeTenants()).hasSize(1);
        assertThatThrownBy(() -> catalog.activeTenants().add(TenantId.of("gamma")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
