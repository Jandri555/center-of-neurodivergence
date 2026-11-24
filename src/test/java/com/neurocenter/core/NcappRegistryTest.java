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
        assertEquals("Demo", registry.listApps().get(0).toString(),
                "El selector de permisos no debe mostrar el inicio automático");
        assertTrue(registry.securityProfilesByJar().containsKey("demo.ncapp"));
    }

    @Test
    void threeStartupModesPersistAndDoNotOverwritePermissions() throws Exception {
        Path archive = sandbox.resolve("demo.ncapp");
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("NeuroCenter-App-Id", "demo.app");
        attrs.putValue("NeuroCenter-App-Desktop", "true");
        attrs.putValue("Implementation-Title", "Demo");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive), mf)) {
            jar.putNextEntry(new JarEntry("META-INF/services/com.neurocenter.api.NeuroApp"));
            jar.write("com.example.DemoApp\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path db = sandbox.resolve("startup.db");
        AppRegistry reg = new AppRegistry(db);
        reg.registerJar(archive);
        assertEquals(AppRegistry.StartupMode.NO, reg.listApps().get(0).startupMode());
        reg.setStartupMode("demo.app", AppRegistry.StartupMode.DELAYED);
        assertEquals(AppRegistry.StartupMode.DELAYED, new AppRegistry(db).listApps().get(0).startupMode());
        assertFalse(reg.listApps().get(0).autorun());
        reg.setStartupMode("demo.app", AppRegistry.StartupMode.YES);
        assertEquals(AppRegistry.StartupMode.YES, reg.startupModesByJar().get("demo.ncapp"));
        assertTrue(reg.listApps().get(0).autorun());
        reg.setStartupMode("demo.app", AppRegistry.StartupMode.NO);
        assertEquals(AppRegistry.StartupMode.NO, new AppRegistry(db).startupModesByJar().get("demo.ncapp"));
        assertThrows(java.sql.SQLException.class, () -> reg.setStartupModes(
                java.util.Map.of("demo.app", AppRegistry.StartupMode.YES,
                                 "missing.app", AppRegistry.StartupMode.DELAYED)));
        assertEquals(AppRegistry.StartupMode.NO, reg.listApps().get(0).startupMode(),
                "Un guardado fallido no puede dejar cambios parciales");
    }

    @Test
    void migratesPreviousBooleanAutorunWithoutLosingExistingAppSettings() throws Exception {
        Path db = sandbox.resolve("legacy.db");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE installed_apps (" +
                    "id TEXT PRIMARY KEY, name TEXT, author TEXT, version TEXT, " +
                    "jar_filename TEXT, installed_at TEXT, size_bytes INTEGER, " +
                    "autorun INTEGER, declared_permissions TEXT)");
            statement.execute("INSERT INTO installed_apps VALUES (" +
                    "'legacy', 'Legacy app', 'Someone', '1.0', 'legacy.ncapp', " +
                    "'2026-11-24', 24, 1, '')");
        }
        AppRegistry reg = new AppRegistry(db);
        assertEquals(AppRegistry.StartupMode.YES, reg.listApps().get(0).startupMode());
        reg.setStartupMode("legacy", AppRegistry.StartupMode.DELAYED);
        assertEquals(AppRegistry.StartupMode.DELAYED, new AppRegistry(db).listApps().get(0).startupMode());
    }

    @Test
    void rejectsLegacyJarExtension() throws Exception {
        Path archive = sandbox.resolve("legacy.jar");
        Files.writeString(archive, "not an application");
        AppRegistry registry = new AppRegistry(sandbox.resolve("apps.db"));
        assertThrows(IOException.class, () -> registry.registerJar(archive));
    }
}
