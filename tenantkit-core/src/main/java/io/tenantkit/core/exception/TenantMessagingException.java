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
 * Messaging propagation failures (TK-3xxx family, reserved for the messaging
 * module: listener rejected a message for a missing/invalid tenant header).
 * Thrown by {@code tenantkit-messaging} from M2 onward (DESIGN &sect;7).
 */
public class TenantMessagingException extends TenantKitException {

    public TenantMessagingException(String errorCode, String message) {
        super(errorCode, message);
    }

    public TenantMessagingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
