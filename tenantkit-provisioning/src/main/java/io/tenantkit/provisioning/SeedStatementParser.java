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

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a seed script into single statements on {@code ;} separators
 * (DESIGN &sect;6.5: one script, many statements, executed one by one).
 *
 * <p>Semicolons inside quoted literals do <em>not</em> split: the scanner
 * tracks single quotes (with the {@code ''} escape), double quotes, {@code --}
 * line comments and {@code /* *}{@code /} block comments. <strong>Declared
 * boundaries</strong> (keep seed scripts inside them): no dollar-quoted
 * strings, no {@code DELIMITER} directives (stored routines are out of scope
 * for tenant seeds), no backslash-quote escaping dialects &mdash; a literal
 * containing an odd {@code ''}-escaped quote pair still scans correctly, but
 * exotic dialects should stay out of tenant seed scripts.
 *
 * <p>Comments are preserved in the emitted statements (engines handle them
 * fine); empty statements (trailing semicolons, blank stretches) are dropped.
 */
final class SeedStatementParser {

    private SeedStatementParser() {
    }

    /** Splits on semicolons that sit outside literals and comments. */
    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = consumeQuoted(sql, i, current);
            }
            else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = consumeLineComment(sql, i, current);
            }
            else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = consumeBlockComment(sql, i, current);
            }
            else if (c == ';') {
                appendIfNotBlank(statements, current);
                i++;
            }
            else {
                current.append(c);
                i++;
            }
        }
        appendIfNotBlank(statements, current);
        return statements;
    }

    private static int consumeQuoted(String sql, int start, StringBuilder current) {
        char quote = sql.charAt(start);
        current.append(sql.charAt(start));
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            current.append(c);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    current.append(sql.charAt(i + 1)); // '' / "" escape keeps the literal open
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i; // unterminated literal: scanner ends with the text consumed
    }

    private static int consumeLineComment(String sql, int start, StringBuilder current) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            current.append(sql.charAt(i));
            i++;
        }
        return i;
    }

    private static int consumeBlockComment(String sql, int start, StringBuilder current) {
        current.append(sql.charAt(start)).append(sql.charAt(start + 1));
        int i = start + 2;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            current.append(c);
            if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                current.append('/');
                return i + 2;
            }
            i++;
        }
        return i;
    }

    private static void appendIfNotBlank(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isBlank()) {
            statements.add(statement);
        }
    }
}
