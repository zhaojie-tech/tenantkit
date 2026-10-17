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
package io.tenantkit.core.exception;

/**
 * Base of the TenantKit exception hierarchy. Every subclass carries a stable
 * machine-readable error code from the TK families documented in the reference
 * guide: TK-100x resolution, TK-2xxx provisioning (reserved), TK-3xxx
 * messaging (reserved) (DESIGN &sect;7).
 */
public abstract class TenantKitException extends RuntimeException {

    private final String errorCode;

    protected TenantKitException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected TenantKitException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Stable error code, e.g. {@code TK-1001}. Never {@code null}. */
    public String errorCode() {
        return errorCode;
    }
}
