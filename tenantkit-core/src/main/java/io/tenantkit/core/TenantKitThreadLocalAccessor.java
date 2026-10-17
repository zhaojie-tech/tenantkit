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
package io.tenantkit.core;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link TenantContext} into Micrometer context-propagation so
 * {@code ContextSnapshot} capture/restore includes the tenant (DESIGN
 * &sect;6.3). Only loaded when context-propagation is present. Uses the
 * package-private {@link TenantContext#unsafeSet} hook &mdash; the public API
 * surface of {@code TenantContext} stays mutation-free.
 */
final class TenantKitThreadLocalAccessor implements ThreadLocalAccessor<TenantId> {

    @Override
    public Object key() {
        return TenantContextPropagation.CONTEXT_KEY;
    }

    @Override
    public TenantId getValue() {
        return TenantContext.currentOrNull();
    }

    @Override
    public void setValue(TenantId value) {
        TenantContext.unsafeSet(value);
    }

    @Override
    public void setValue() {
        TenantContext.unsafeSet(null);
    }
}
