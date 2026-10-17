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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ledger checksum computation (DESIGN &sect;6.5 / ADR-0001): SHA-256 over the
 * normalized script content with the file name mixed in. Line endings are
 * normalized CRLF&rarr;LF (and a lone CR&rarr;LF) so a file checked out with
 * different git settings or edited on another OS keeps the same checksum &mdash;
 * only real content changes must trip the TK-2001 refusal.
 */
final class SeedChecksums {

    private SeedChecksums() {
    }

    /** SHA-256 hex of {@code scriptName + "\n" + normalize(sql)}. */
    static String of(String scriptName, String sql) {
        return hex(sha256(scriptName + "\n" + normalize(sql)));
    }

    /** CRLF and lone CR normalized to LF, so line endings never move a checksum. */
    static String normalize(String sql) {
        return sql.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM without SHA-256", ex);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
