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
 * Message header carrying the tenant across the broker (DESIGN &sect;6.3).
 * The constant is public on purpose: producers in other languages must align
 * on the exact wire value, so it is documented as the cross-language contract,
 * not an implementation detail.
 */
public final class TenantMessagingHeaders {

    /**
     * Tenant header written by {@link TenantHeaderMessagePostProcessor} and
     * read by {@link TenantContextAdvice}. Lower-case wire value
     * ({@code x-tenant-id}); AMQP headers are case-sensitive strings, so the
     * constant must be used verbatim.
     */
    public static final String TENANT_HEADER = "x-tenant-id";

    private TenantMessagingHeaders() {
    }
}
