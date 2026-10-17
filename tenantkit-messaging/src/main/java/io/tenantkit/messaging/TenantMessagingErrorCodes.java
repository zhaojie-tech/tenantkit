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
package io.tenantkit.messaging;

/**
 * Stable machine-readable error codes carried by
 * {@link io.tenantkit.core.exception.TenantMessagingException} (DESIGN
 * &sect;7). Code families: TK-100x resolution, TK-2xxx provisioning,
 * TK-3xxx messaging.
 */
public final class TenantMessagingErrorCodes {

    /** Publisher sent with no tenant bound to the context (policy {@code fail}). */
    public static final String PUBLISHER_NO_TENANT = "TK-3001";

    /** Listener received a message without the tenant header. */
    public static final String LISTENER_MISSING_HEADER = "TK-3002";

    /** Listener received a header value that fails the {@code TenantId} charset. */
    public static final String LISTENER_INVALID_HEADER = "TK-3003";

    private TenantMessagingErrorCodes() {
    }
}
