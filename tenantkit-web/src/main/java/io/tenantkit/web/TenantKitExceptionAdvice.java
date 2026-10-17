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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantKitException;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import io.tenantkit.core.exception.TenantMissingException;
import io.tenantkit.core.exception.UnknownTenantException;

/**
 * Runtime exit for {@link TenantKitException} family raised inside MVC code
 * (e.g. {@code TenantContext.required()} in a controller, or Redis strict
 * policy): RFC 9457 ProblemDetail with the same stable codes the resolution
 * filter uses. The filter covers pre-handler failures; this advice covers
 * everything after handler selection (R1 architecture review P2-2).
 *
 * <p>Status mapping: TK-1001 400, TK-1002 uses
 * {@code tenantkit.resolution.unknown-tenant-status}, everything else 500 —
 * provisioning/messaging exceptions reaching a web handler are server errors.
 */
@RestControllerAdvice
@ConditionalOnClass(jakarta.servlet.Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "tenantkit.web.enabled", havingValue = "true", matchIfMissing = true)
public class TenantKitExceptionAdvice {

    private final TenantKitResolutionProperties properties;

    public TenantKitExceptionAdvice(TenantKitResolutionProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(TenantKitException.class)
    public ResponseEntity<ProblemDetail> onTenantKitException(TenantKitException exception) {
        int status;
        if (exception instanceof TenantMissingException) {
            status = 400;
        }
        else if (exception instanceof UnknownTenantException) {
            status = properties.getUnknownTenantStatus();
        }
        else if (exception instanceof TenantIdFormatException) {
            status = 400;
        }
        else {
            status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        return ResponseEntity.status(status).body(ProblemDetails.of(exception, status));
    }
}
