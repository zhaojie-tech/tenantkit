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
package io.tenantkit.web.condition;

import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code tenantkit.tenants} binds to a non-empty list. Works for
 * both the indexed ({@code tenantkit.tenants[0]=a}) and inline
 * ({@code tenantkit.tenants: [a, b]}) property forms, unlike
 * {@code @ConditionalOnProperty} which only sees the exact key.
 */
public class OnTenantsConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (context.getEnvironment() == null) {
            return false;
        }
        List<String> tenants = Binder.get(context.getEnvironment())
                .bind("tenantkit.tenants", Bindable.listOf(String.class))
                .orElse(List.of());
        return !tenants.isEmpty();
    }
}
