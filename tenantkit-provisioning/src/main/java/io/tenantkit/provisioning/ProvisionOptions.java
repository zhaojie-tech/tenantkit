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
package io.tenantkit.provisioning;

import java.time.Duration;

/**
 * Per-call overrides for {@link TenantProvisioner#provision}. Configuration
 * ({@code tenantkit.provisioning.*}) supplies the defaults; a non-{@code null}
 * field here overrides it for this call (DESIGN &sect;6.5 &mdash; frozen
 * signature).
 *
 * <p>Convenience factories:
 * {@link #defaults()} for the pure-configuration call and
 * {@link #auditOnly()} for the dry-run call.
 *
 * @param dryRun parse scripts, replace placeholders and emit the audit log
 *        without executing SQL or touching the ledger; {@code null} uses the
 *        configured {@code tenantkit.provisioning.dry-run} default
 * @param lockTimeout how long to wait for the tenant provisioning lock before
 *        failing with TK-2002; {@code null} uses the configured
 *        {@code tenantkit.provisioning.lock-timeout-seconds} default
 */
public record ProvisionOptions(Boolean dryRun, Duration lockTimeout) {

    public ProvisionOptions {
        if (lockTimeout != null && lockTimeout.isNegative()) {
            // GET_LOCK(t, negative) means INFINITE wait in MySQL semantics
            throw new IllegalArgumentException("lockTimeout must not be negative");
        }
    }

    /** Options that inherit every value from configuration. */
    public static ProvisionOptions defaults() {
        return new ProvisionOptions(null, null);
    }

    /** Audit-only run: parse, replace and log, never execute or record. */
    public static ProvisionOptions auditOnly() {
        return new ProvisionOptions(true, null);
    }
}
