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
package io.tenantkit.test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test classloader that hides packages, simulating "dependency absent" cases
 * in assembly matrices (Security absent, spring-tx absent, micrometer absent).
 *
 * <p>Carries the full {@code java.class.path} so Spring's ASM metadata reader
 * and the ApplicationContextRunner keep working, and loads TenantKit classes
 * child-first so the hidden visibility actually applies to them.
 */
public final class HidingClassLoader extends URLClassLoader {

    private final String[] hiddenPrefixes;

    public HidingClassLoader(ClassLoader parent, String... hiddenPrefixes) {
        super(fullTestClasspath(), parent);
        this.hiddenPrefixes = Arrays.copyOf(hiddenPrefixes, hiddenPrefixes.length);
    }

    private static URL[] fullTestClasspath() {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                urls.add(new File(entry).toURI().toURL());
            }
            catch (Exception ex) {
                throw new IllegalStateException("bad classpath entry: " + entry, ex);
            }
        }
        return urls.toArray(URL[]::new);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String prefix : this.hiddenPrefixes) {
            if (name.startsWith(prefix)) {
                throw new ClassNotFoundException(name + " (hidden by test to simulate absence)");
            }
        }
        // child-first for kit classes: otherwise parent-first delegation returns the
        // parent's already-loaded copy whose loader still sees every dependency
        if (name.startsWith("io.tenantkit.")) {
            return findClass(name);
        }
        return super.loadClass(name, resolve);
    }
}
