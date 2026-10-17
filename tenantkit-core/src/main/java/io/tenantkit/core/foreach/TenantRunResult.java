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

import io.tenantkit.core.TenantId;

/**
 * Per-tenant outcome inside a {@link ForEachReport}.
 *
 * @param tenant the tenant this entry describes
 * @param success {@code true} when the action completed without throwing
 * @param failure the thrown exception, {@code null} on success
 * @param elapsed wall-clock time of the tenant run
 */
public record TenantRunResult(TenantId tenant, boolean success, Throwable failure, Duration elapsed) {

    public TenantRunResult {
        if (success && failure != null) {
            throw new IllegalArgumentException("a successful run must not carry a failure");
        }
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }
}
