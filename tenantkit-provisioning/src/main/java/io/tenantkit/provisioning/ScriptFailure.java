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
package io.tenantkit.provisioning;

/**
 * One seed script whose execution failed. Frozen as part of the
 * {@link ProvisionReport} signature (DESIGN &sect;6.5). On any script failure
 * the whole provisioning fails and the report is reachable from
 * {@link ProvisionFailedException#report()}.
 *
 * @param scriptName script file name whose execution failed
 * @param reason failure description: exception class plus message
 */
public record ScriptFailure(String scriptName, String reason) {
}
