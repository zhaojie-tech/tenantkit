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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link org.springframework.http.ProblemDetail} bodies with a
 * stable, identical shape on every environment: {@code type}, {@code title},
 * {@code status}, {@code detail}, top-level {@code errorCode}.
 *
 * <p>Hand-written on purpose: the writer must work with <em>zero</em> JSON
 * classpath coupling. An earlier revision consulted Jackson 2 when present —
 * on Spring Boot 4 (which ships Jackson 3 under {@code tools.jackson}) the
 * mere presence of a {@code com.fasterxml.jackson} type in a bean-method
 * signature broke context startup for consumers without Jackson 2 on the
 * classpath (R4 consumer test, P0). The wire contract never depends on the
 * classpath now, and a dedicated test pins the exact bytes.
 *
 * <p>Rationale for filter-level emission (review question we expect): tenant
 * resolution fails <em>before handler selection</em>, so MVC exception
 * resolution ({@code @ExceptionHandler}/{@code ResponseEntityExceptionHandler})
 * does not apply &mdash; the same reason Spring Security uses its own
 * authentication entry point. RFC 9457 content negotiation is intentionally
 * narrow in v0.1: {@code application/problem+json} only, no format variants.
 * Runtime (post-handler) exceptions are covered by
 * {@link TenantKitExceptionAdvice} through Spring MVC's own ProblemDetail
 * serialization.
 */
public final class TenantProblemJsonWriter {

    private TenantProblemJsonWriter() {
    }

    public static String write(org.springframework.http.ProblemDetail detail, String errorCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", String.valueOf(detail.getType()));
        body.put("title", detail.getTitle());
        body.put("status", detail.getStatus());
        body.put("detail", detail.getDetail());
        body.put("errorCode", errorCode);
        return "{" + "\"type\":\"" + escape(String.valueOf(body.get("type")))
                + "\",\"title\":\"" + escape(String.valueOf(body.get("title")))
                + "\",\"status\":" + body.get("status")
                + ",\"detail\":\"" + escape(String.valueOf(body.get("detail")))
                + "\",\"errorCode\":\"" + escape(String.valueOf(body.get("errorCode"))) + "\"}";
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
