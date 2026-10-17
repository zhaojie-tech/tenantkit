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

import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * Raised when at least one seed script fails during provisioning: the whole
 * provisioning for the tenant fails (TK-2003), the ledger keeps its current
 * state &mdash; already-successful scripts are neither rolled back nor
 * replayed &mdash; and the failing scripts are detailed in
 * {@link #report()}{@code .failed()}.
 */
public final class ProvisionFailedException extends TenantProvisioningException {

    private final ProvisionReport report;

    ProvisionFailedException(ProvisionReport report, Throwable cause) {
        this(report, cause, "TK-2003");
    }

    /**
     * Overload for failures that are not script failures (e.g. checksum divergence
     * under concurrency, which is TK-2001) — the exception's {@code errorCode()}
     * and the underlying cause must agree (R4 P2 taxonomy fix).
     */
    ProvisionFailedException(ProvisionReport report, Throwable cause, String errorCode) {
        super(errorCode, "tenant provisioning failed on script '" + report.failed().get(0).scriptName()
                + "': " + report.failed().get(0).reason()
                + "; earlier scripts of this run are committed and stay recorded (idempotent ledger semantics)",
                cause);
        this.report = report;
    }

    /** Report with the executed / skipped / failed split of the failed run. */
    public ProvisionReport report() {
        return report;
    }
}
