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
 * No tenant is bound to the current execution context (error code
 * {@code TK-1001}). Default resolution outcome is fail-fast: silently falling
 * back to a default tenant is how cross-tenant data leaks are born (DESIGN
 * &sect;6.1). Public endpoints must opt out explicitly via {@code @TenantPublic}
 * or the {@code ignore-paths} table.
 */
public class TenantMissingException extends TenantKitException {

    public TenantMissingException(String message) {
        super("TK-1001", message);
    }
}
