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
package io.tenantkit.core.foreach;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Options for {@link Tenants#forEach}. Configuration supplies defaults,
 * construction-time values override (DESIGN &sect;6.6).
 *
 * @param perTenantTimeout per-tenant wall-clock limit; {@code null} = no
 *        timeout. "A slow tenant must not drag the whole batch" holds only
 *        when this is set explicitly &mdash; the default serial run has no
 *        timeout and this is stated as-is in the docs.
 * @param executor executor used when a timeout is configured; {@code null} =
 *        an internal daemon executor is used for timed runs (the default
 *        serial path never off-threads)
 * @param continueOnError {@code true} (default): a failing tenant is recorded
 *        in the {@link ForEachReport} and the batch continues; {@code false}:
 *        the batch aborts and the failure propagates
 * @param failOnOuterTransaction {@code false} (default): an active outer
 *        transaction is detected and WARN-logged; {@code true}: detection
 *        throws instead (running every tenant inside one outer transaction
 *        couples tenants and rolls all back together)
 */
public record ForEachOptions(Duration perTenantTimeout, Executor executor,
        boolean continueOnError, boolean failOnOuterTransaction) {

    public ForEachOptions {
        if (perTenantTimeout != null && perTenantTimeout.isNegative()) {
            throw new IllegalArgumentException("perTenantTimeout must not be negative");
        }
    }

    /** Serial, no timeout, failure isolation aggregated, outer transaction = WARN. */
    public static ForEachOptions defaults() {
        return new ForEachOptions(null, null, true, false);
    }
}
