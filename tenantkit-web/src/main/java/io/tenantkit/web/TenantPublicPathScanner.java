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

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Builds the {@code @TenantPublic} exemption path set at startup by scanning
 * {@link RequestMappingHandlerMapping} (DESIGN &sect;6.2: the filter runs
 * before the handler method is determined, so annotations must be pre-built
 * into a path set). Matching uses Ant patterns against the lookup path.
 */
public final class TenantPublicPathScanner {

    private TenantPublicPathScanner() {
    }

    public static TenantResolutionFilter.TenantPublicPaths scan(RequestMappingHandlerMapping mapping) {
        Set<String> paths = new LinkedHashSet<>();
        mapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod handlerMethod) -> {
            boolean annotated = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(),
                    TenantPublic.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(),
                            TenantPublic.class) != null;
            if (annotated) {
                // apps may revert to AntPathMatcher mappings, where this condition is null
                if (info.getPathPatternsCondition() != null) {
                    paths.addAll(info.getPathPatternsCondition().getPatternValues());
                }
            }
        });
        return new AntMatcherPaths(paths);
    }

    private record AntMatcherPaths(Set<String> patterns) implements TenantResolutionFilter.TenantPublicPaths {

        private static final PathMatcher MATCHER = new AntPathMatcher();

        @Override
        public boolean isPublic(String lookupPath) {
            return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, lookupPath));
        }
    }
}
