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

import org.springframework.http.ProblemDetail;

import io.tenantkit.core.exception.TenantKitException;

/**
 * RFC 9457 ProblemDetail factory with stable type URIs and an
 * {@code errorCode} property carrying the TK-xxxx code (DESIGN &sect;6.2).
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(TenantKitException exception, int status) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(status), exception.getMessage());
        detail.setType(java.net.URI.create(TenantKitErrorCodes.TYPE_URI_BASE + exception.errorCode()));
        detail.setTitle(exception.errorCode());
        detail.setProperty("errorCode", exception.errorCode());
        return detail;
    }
}
