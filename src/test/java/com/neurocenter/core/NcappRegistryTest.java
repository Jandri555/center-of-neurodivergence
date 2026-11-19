package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class NcappRegistryTest {
    @TempDir Path sandbox;

    @Test
    void installsNcappUsingNormalJarBytecodeFormat() throws Exception {
        Path archive = sandbox.resolve("demo.ncapp");
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("NeuroCenter-App-Id", "demo.app");
        attrs.putValue("NeuroCenter-App-Desktop", "true");
        attrs.putValue("NeuroCenter-App-Mobile", "true");
        attrs.putValue("Implementation-Title", "Demo");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive), mf)) {
            jar.putNextEntry(new JarEntry("META-INF/services/com.neurocenter.api.NeuroApp"));
            jar.write("com.example.DemoApp\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        AppRegistry registry = new AppRegistry(sandbox.resolve("apps.db"));
        registry.registerJar(archive);
        assertEquals("demo.app", registry.listApps().get(0).id());
        assertEquals("demo.ncapp", registry.listApps().get(0).jarFilename());
        assertTrue(registry.securityProfilesByJar().containsKey("demo.ncapp"));
    }

    @Test
    void rejectsLegacyJarExtension() throws Exception {
        Path archive = sandbox.resolve("legacy.jar");
        Files.writeString(archive, "not an application");
        AppRegistry registry = new AppRegistry(sandbox.resolve("apps.db"));
        assertThrows(IOException.class, () -> registry.registerJar(archive));
    }
}
