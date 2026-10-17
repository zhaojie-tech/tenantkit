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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import io.tenantkit.core.exception.TenantProvisioningException;

/**
 * Locates and orders a profile's seed scripts:
 * {@code <seed-location>/<profile>/V<n>__<description>.sql}, ordered by the
 * {@code V} number (DESIGN &sect;6.5). Read through
 * {@link PathMatchingResourcePatternResolver} (spring-core), so
 * {@code classpath:} and {@code file:} seed locations both work.
 *
 * <p>Tolerates gaps in the numbering with a WARN (deleted scripts happen);
 * files that do not match the {@code V<n>__*.sql} shape are ignored with a
 * WARN rather than executed in arbitrary order. A profile that resolves to no
 * scripts at all fails with TK-2005 &mdash; an empty seed set is exactly the
 * "tenant four tables lack seed data" incident.
 */
public class SeedScriptLocator {

    private static final Logger log = LoggerFactory.getLogger(SeedScriptLocator.class);

    /** Flyway-style {@code V<n>__description.sql} name. */
    private static final Pattern SCRIPT_NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private final ResourcePatternResolver resolver;
    private final String seedLocation;

    public SeedScriptLocator(String seedLocation) {
        this(new PathMatchingResourcePatternResolver(), seedLocation);
    }

    public SeedScriptLocator(ResourcePatternResolver resolver, String seedLocation) {
        this.resolver = resolver;
        this.seedLocation = seedLocation;
    }

    /**
     * Loads the profile's scripts, sorted by {@code V} number (ties broken by
     * file name for stable checksums).
     *
     * @throws TenantProvisioningException TK-2005 when the location is
     *         unreadable or resolves to no scripts
     */
    public List<SeedScript> locate(SeedProfile profile) {
        String base = seedLocation.endsWith("/") ? seedLocation : seedLocation + "/";
        String pattern = base + profile.name() + "/V*.sql";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        }
        catch (IOException ex) {
            throw new TenantProvisioningException("TK-2005",
                    "seed location '" + seedLocation + "' is not readable for profile '" + profile.name() + "'", ex);
        }
        List<SeedScript> scripts = new ArrayList<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name == null) {
                continue;
            }
            Matcher matcher = SCRIPT_NAME.matcher(name);
            if (!matcher.matches()) {
                log.warn("ignoring seed file '{}' that does not match V<n>__<description>.sql", name);
                continue;
            }
            String sql = read(resource, name);
            scripts.add(new SeedScript(name, Integer.parseInt(matcher.group(1)), sql, SeedChecksums.of(name, sql)));
        }
        if (scripts.isEmpty()) {
            throw new TenantProvisioningException("TK-2005", "no seed scripts found for profile '" + profile.name()
                    + "' under '" + seedLocation + "'; an empty seed set is refused (this is the 'tenant tables "
                    + "lack seed data' incident the module prevents) - check tenantkit.provisioning.seed-location "
                    + "and the profile name");
        }
        scripts.sort(Comparator.comparingInt(SeedScript::sequence).thenComparing(SeedScript::name));
        warnAboutGaps(profile, scripts);
        return List.copyOf(scripts);
    }

    private void warnAboutGaps(SeedProfile profile, List<SeedScript> sorted) {
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            int previous = sorted.get(i - 1).sequence();
            int current = sorted.get(i).sequence();
            for (int missing = previous + 1; missing < current; missing++) {
                gaps.add(missing);
            }
        }
        if (!gaps.isEmpty()) {
            log.warn("seed profile '{}' has gaps in its V-numbering (missing: {}); "
                    + "scripts still run in the order present", profile.name(), gaps);
        }
    }

    private String read(Resource resource, String name) {
        try {
            return SeedChecksums.normalize(new String(resource.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new TenantProvisioningException("TK-2005", "seed script '" + name + "' is not readable", ex);
        }
    }
}
