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
 * Result of one successfully handled seed script. Frozen as part of the
 * {@link ProvisionReport} signature (DESIGN &sect;6.5).
 *
 * @param scriptName script file name, e.g. {@code V1__insert_core_data.sql}
 * @param checksum SHA-256 over the normalized script content with the file
 *        name mixed in &mdash; the same value stored in the seed ledger
 */
public record ScriptResult(String scriptName, String checksum) {
}
