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

import java.util.Optional;

/**
 * Resolver SPI. Strategies run as a chain ordered by {@link #order()}; the
 * first resolver returning a non-empty {@link TenantId} wins (DESIGN
 * &sect;6.1). Custom resolvers are registered as beans and picked up by the
 * resolving auto-configuration.
 */
public interface TenantResolver {

    /**
     * Resolves a tenant from the request snapshot. Returning
     * {@link Optional#empty()} means "not from me" and hands over to the next
     * strategy; implementations must not throw for "absent" &mdash; throw only
     * for "present but invalid" (the filter converts those to TK-1003).
     */
    Optional<TenantId> resolve(TenantResolutionRequest request);

    /** Chain position; lower runs first. */
    int order();
}
