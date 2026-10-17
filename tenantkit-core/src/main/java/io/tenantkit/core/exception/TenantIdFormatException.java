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
 * A tenant source produced a value violating the configured id charset
 * (error code {@code TK-1003}, HTTP 400).
 */
public class TenantIdFormatException extends TenantKitException {

    public TenantIdFormatException(String message) {
        super("TK-1003", message);
    }
}
