package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for declared-only grants and remembered decisions. */
class AppPermissionManifestTest {
    @TempDir Path temp;

    private Path app(String name, String declared) throws IOException {
        Path path = temp.resolve(name + ".ncapp");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("NeuroCenter-App-Id", name);
        attrs.putValue("NeuroCenter-App-Desktop", "true");
        attrs.putValue("NeuroCenter-App-Mobile", "false");
        if (declared != null) attrs.putValue("NeuroCenter-App-Permissions", declared);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/services/com.neurocenter.api.NeuroApp"));
            output.write("com.test.TestApp\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    @Test
    void onlyDeclaredPermissionsCanBeGrantedOrDeniedPermanently() throws Exception {
        AppRegistry registry = new AppRegistry(temp.resolve("registry.db"));
        registry.registerJar(app("aula", "network.connect,credentials.access,agenda.write"));
        assertEquals(Set.of("network.connect", "credentials.access", "agenda.write"),
                registry.declaredPermissions("aula"));
        assertEquals(Set.of(), registry.grantedPermissions("aula"));
        assertNull(registry.storedDecision("aula", "network.connect"));

        registry.setDecision("aula", "network.connect", true);
        assertEquals(Boolean.TRUE, registry.storedDecision("aula", "network.connect"));
        assertEquals(Set.of("network.connect"), registry.grantedPermissions("aula"));

        registry.setDecision("aula", "network.connect", false);
        assertEquals(Boolean.FALSE, registry.storedDecision("aula", "network.connect"));
        assertEquals(Set.of(), registry.grantedPermissions("aula"));

        registry.setDecision("aula", "network.connect", null);
        assertNull(registry.storedDecision("aula", "network.connect"));

        assertThrows(SecurityException.class,
                () -> registry.setDecision("aula", "clipboard.access", true));
        assertThrows(SecurityException.class,
                () -> registry.setGrantedPermissions("aula", Set.of("clipboard.access")));
    }

    @Test
    void missingManifestDeclarationMeansNoPermissions() throws Exception {
        AppRegistry registry = new AppRegistry(temp.resolve("registry.db"));
        registry.registerJar(app("minimal", null));
        assertEquals(Set.of(), registry.declaredPermissions("minimal"));
        assertThrows(SecurityException.class,
                () -> registry.setDecision("minimal", "network.connect", true));
    }

    @Test
    void unknownAndDuplicateManifestPermissionsAreRejectedAtInstall() throws Exception {
        AppRegistry registry = new AppRegistry(temp.resolve("registry.db"));
        assertThrows(IOException.class,
                () -> registry.registerJar(app("invalid", "network.connect,system.superuser")));
        assertThrows(IOException.class,
                () -> registry.registerJar(app("duplicate", "network.connect,network.connect")));
    }

    @Test
    void storedPermissionsAreLimitedByManifestAndPersistBetweenRegistryOpens() throws Exception {
        Path database = temp.resolve("registry.db");
        AppRegistry first = new AppRegistry(database);
        first.registerJar(app("persist", "credentials.access"));
        first.setDecision("persist", "credentials.access", true);
        AppRegistry reopened = new AppRegistry(database);
        assertEquals(Set.of("credentials.access"), reopened.grantedPermissions("persist"));
        assertEquals(Set.of("credentials.access"),
                reopened.securityProfilesByJar().get("persist.ncapp").declaredPermissions());
    }
}
