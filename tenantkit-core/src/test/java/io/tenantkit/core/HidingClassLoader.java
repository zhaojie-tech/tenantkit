package io.tenantkit.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/**
 * Test classloader that hides packages, used to simulate "dependency absent"
 * matrix cases (K02: spring-tx absent, K04: micrometer absent, and the web
 * module's "Security absent" case with the same utility).
 */
public final class HidingClassLoader extends URLClassLoader {

    private final String[] hiddenPrefixes;

    public HidingClassLoader(ClassLoader parent, String... hiddenPrefixes) {
        super(classpathOf(parent), parent);
        this.hiddenPrefixes = Arrays.copyOf(hiddenPrefixes, hiddenPrefixes.length);
    }

    private static URL[] classpathOf(ClassLoader parent) {
        try {
            URL location = TenantId.class.getProtectionDomain().getCodeSource().getLocation();
            return new URL[] { location };
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("cannot locate tenantkit-core classes for the hiding loader", ex);
        }
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
