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

import java.util.List;

import io.tenantkit.core.TenantId;

/**
 * Outcome of a provisioning (or repair) call. Frozen signature (DESIGN
 * &sect;6.5).
 *
 * <p>A dry run returns the empty report (nothing executed, nothing skipped);
 * the plan itself goes to the audit log.
 *
 * @param tenant the provisioned tenant
 * @param executed scripts this call executed and recorded; for
 *        {@link TenantProvisioner#repair} these are the scripts whose
 *        checksums were re-recorded (repair never executes seed SQL)
 * @param skipped scripts already recorded with a matching checksum, or &mdash;
 *        when {@code duplicateKeyShortCircuit} is {@code true} &mdash; scripts
 *        another instance recorded while this call was running
 * @param failed scripts whose execution failed; the provisioning call throws
 *        {@link ProvisionFailedException} carrying this report
 * @param duplicateKeyShortCircuit {@code true} when a ledger insert hit the
 *        {@code UNIQUE KEY (tenant_id, script_name)} because another instance
 *        had already recorded the script &mdash; an idempotent success
 *        ("the other instance did the bookkeeping"), never a failure
 */
public record ProvisionReport(TenantId tenant, List<ScriptResult> executed, List<ScriptResult> skipped,
        List<ScriptFailure> failed, boolean duplicateKeyShortCircuit) {

    public ProvisionReport {
        executed = executed == null ? List.of() : List.copyOf(executed);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }

    /** {@code true} when no script failed &mdash; including short-circuit runs. */
    public boolean succeeded() {
        return failed.isEmpty();
    }
}
