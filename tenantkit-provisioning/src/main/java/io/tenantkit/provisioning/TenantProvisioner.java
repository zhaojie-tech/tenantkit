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

import io.tenantkit.core.TenantId;

/**
 * Idempotent tenant provisioning: seed scripts checksummed against a per-tenant
 * ledger under a tenant-scoped advisory lock (DESIGN &sect;6.5, ADR-0001).
 *
 * <p>Calling {@link #provision} repeatedly is safe and is the normal operation
 * mode: scripts recorded with a matching checksum are skipped; a recorded
 * checksum that no longer matches the script content is refused outright
 * (TK-2001) &mdash; replaying silently would rewrite history. A ledger insert
 * that loses a race against another instance is an idempotent success and is
 * reported via {@link ProvisionReport#duplicateKeyShortCircuit()}.
 *
 * <p>The explicit escape hatch for a legitimately revised seed script is
 * {@link #repair}: it re-records checksums with an audit diff in the logs. It
 * never executes seed SQL; to ship changed content to existing tenants, add a
 * new {@code V<n+1>__*.sql} script.
 */
public interface TenantProvisioner {

    /**
     * Provisions the tenant from the given seed profile, idempotently.
     *
     * <p>Execution model (ADR-0001): a single dedicated physical connection
     * holds {@code GET_LOCK("tenantkit:provision:<tenant>")} for the whole run;
     * every script runs in its own committed transaction on an independent
     * connection; the lock is released on the holding connection in a
     * {@code finally} block. Any script failure fails the whole run while the
     * ledger keeps already-recorded scripts.
     *
     * @param id tenant to provision
     * @param profile seed script set to apply
     * @param options per-call overrides; {@code null} behaves like
     *        {@link ProvisionOptions#defaults()}
     * @return the executed / skipped / failed split
     * @throws io.tenantkit.core.exception.TenantProvisioningException TK-2001
     *         checksum mismatch, TK-2002 lock timeout, TK-2004 missing ledger
     *         table, TK-2005 empty or unknown profile
     * @throws ProvisionFailedException a script failed; the exception carries
     *         the partial {@link ProvisionReport}
     */
    ProvisionReport provision(TenantId id, SeedProfile profile, ProvisionOptions options);

    /**
     * Explicit repair path ("provision --repair"): re-records ledger checksums
     * that no longer match the current script content, writing an audit diff
     * (script, old checksum, new checksum) to the log. Use it only after
     * verifying the script change is legitimate &mdash; the checksum refusal
     * exists so a modified script cannot silently replay over an existing
     * tenant.
     *
     * <p>Repair reconciles the ledger only; it never executes seed SQL. To
     * ship changed content to already-provisioned tenants, add a new
     * {@code V<n+1>__*.sql} script instead.
     *
     * @param id tenant whose ledger to reconcile
     * @param profile seed script set to reconcile against
     * @return report where {@code executed} carries the re-recorded scripts
     *         and {@code skipped} the already-consistent ones; {@code failed}
     *         stays empty (repair performs no SQL execution)
     */
    ProvisionReport repair(TenantId id, SeedProfile profile);
}
