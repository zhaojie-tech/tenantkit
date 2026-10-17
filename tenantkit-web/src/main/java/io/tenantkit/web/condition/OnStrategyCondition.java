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

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

import io.tenantkit.web.properties.TenantKitResolutionProperties.Strategy;

/**
 * Matches when the given {@link Strategy} is part of the configured
 * {@code tenantkit.resolution.strategies} chain. Standard
 * {@code @ConditionalOnProperty} cannot express list membership, and built-in
 * resolver beans must be individually visible in the assembly matrix.
 */
public class OnStrategyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String key = (String) metadata.getAnnotationAttributes(ConditionalOnStrategy.class.getName()).get("value");
        if (key == null || context.getEnvironment() == null) {
            return false;
        }
        List<String> configured = Binder.get(context.getEnvironment())
                .bind("tenantkit.resolution.strategies", Bindable.listOf(String.class))
                .orElse(List.of());
        // normalizes exactly like Boot's lenient enum converter (alnum-only, lowercased),
        // so every spelling that BINDS also REGISTERS (R1 case fix + R4 P1 spelling fix)
        String canonicalKey = io.tenantkit.web.properties.TenantKitResolutionProperties
                .canonicalStrategy(key);
        for (String value : configured) {
            if (canonicalKey.equals(
                    io.tenantkit.web.properties.TenantKitResolutionProperties.canonicalStrategy(value))) {
                return true;
            }
        }
        return false;
    }
}
