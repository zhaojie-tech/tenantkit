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
package io.tenantkit.core;

import java.net.URI;
import java.security.Principal;


import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Container-agnostic snapshot of everything a {@link TenantResolver} may look
 * at (DESIGN &sect;6.1). {@code authentication} uses the JDK {@link Principal}
 * type: at runtime it is Spring Security's {@code Authentication} instance
 * (an {@code JwtAuthenticationToken} is a {@code Principal}); building this
 * record in a Security-free environment never triggers
 * {@link NoClassDefFoundError} (R5 correction).
 */
public record TenantResolutionRequest(URI requestUri, String host,
        MultiValueMap<String, String> headers, Principal authentication) {

    public TenantResolutionRequest {
        headers = headers == null ? new LinkedMultiValueMap<>() : headers;
    }
}
