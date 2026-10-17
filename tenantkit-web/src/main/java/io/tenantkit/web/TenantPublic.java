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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler method (or its controller) as public: requests hitting it
 * still pass the resolution filter and still resolve a tenant when one is
 * present, but a <em>missing</em> tenant no longer fails the request
 * (DESIGN &sect;6.2). Because the filter runs before the handler method is
 * determined, the exemption set is scanned from
 * {@code RequestMappingHandlerMapping} at startup.
 *
 * <p>Lower priority than {@code tenantkit.resolution.ignore-paths}: when both
 * match, ignore-paths wins and resolution is skipped entirely.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantPublic {

}
