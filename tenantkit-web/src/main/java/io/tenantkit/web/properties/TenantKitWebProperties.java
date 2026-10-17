/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.web.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Module switch for the web assembly (DESIGN &sect;5 speed table).
 * {@code false} exits the whole module's auto-configuration; user beans are
 * unaffected.
 */
@ConfigurationProperties(prefix = "tenantkit.web")
public class TenantKitWebProperties {

    /** Master switch; {@code false} disables the module's auto-configuration entirely. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
