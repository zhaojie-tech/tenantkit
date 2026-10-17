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
 * Names the seed script set resolved from
 * {@code <seed-location>/<name>/V<n>__*.sql} (DESIGN &sect;6.5).
 *
 * <p>Frozen signature (DESIGN &sect;6.5 &mdash; do not drift). The name maps
 * one-to-one to a directory below the configured
 * {@code tenantkit.provisioning.seed-location}, so typical profiles are
 * {@code "basic"}, {@code "erp-suite"}, and so on. The profile name becomes a
 * classpath (or file system) path segment: keep it a plain identifier without
 * path separators; a profile directory that holds no matching scripts fails
 * provisioning with a TK-2005 error rather than silently doing nothing &mdash;
 * an empty seed set is precisely the "tenant four tables lack seed data"
 * incident this module exists to prevent.
 *
 * @param name profile directory name below the seed location
 */
public record SeedProfile(String name) {

    public SeedProfile {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            // the name is spliced into a classpath pattern (seedLocation/<name>/V*.sql):
            // traversal and glob characters are rejected at the type boundary
            throw new IllegalArgumentException(
                    "seed profile name must match [A-Za-z0-9_-]{1,64} but was: "
                            + (name == null ? "null" : "length " + name.length()));
        }
    }

    /** Factory mirroring the record constructor, for fluent call sites. */
    public static SeedProfile of(String name) {
        return new SeedProfile(name);
    }
}
