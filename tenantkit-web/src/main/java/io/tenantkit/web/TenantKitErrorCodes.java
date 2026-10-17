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
package io.tenantkit.web;

/**
 * Stable machine-readable error codes surfaced in ProblemDetail bodies
 * (DESIGN &sect;6.2). Code families: TK-100x resolution, TK-2xxx provisioning,
 * TK-3xxx messaging.
 */
public final class TenantKitErrorCodes {

    /**
     * Stable type URI prefix for ProblemDetail {@code type} fields. A URN keeps
     * the contract domain-free and permanent (no dependency on a website that
     * may move); clients should treat it as an opaque identifier.
     */
    public static final String TYPE_URI_BASE = "urn:tenantkit:errors:";

    /** Missing tenant on a non-exempt path. HTTP 400. */
    public static final String TENANT_MISSING = "TK-1001";

    /** Resolved tenant is not part of the catalog. HTTP status configurable. */
    public static final String UNKNOWN_TENANT = "TK-1002";

    /** Resolved value violates the configured tenant-id charset. HTTP 400. */
    public static final String TENANT_ID_INVALID = "TK-1003";

    private TenantKitErrorCodes() {
    }
}
