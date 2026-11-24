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
        reg.setStartupMode("demo.app", AppRegistry.StartupMode.YES);
        assertEquals(AppRegistry.StartupMode.YES, reg.startupModesByJar().get("demo.ncapp"));
        reg.setStartupMode("demo.app", AppRegistry.StartupMode.NO);
        assertEquals(AppRegistry.StartupMode.NO, new AppRegistry(db).startupModesByJar().get("demo.ncapp"));
        assertThrows(java.sql.SQLException.class, () -> reg.setStartupModes(
                java.util.Map.of("demo.app", AppRegistry.StartupMode.YES,
                                 "missing.app", AppRegistry.StartupMode.DELAYED)));
        assertEquals(AppRegistry.StartupMode.NO, reg.listApps().get(0).startupMode(),
                "Un guardado fallido no puede dejar cambios parciales");
    }

    @Test
    void updatesExistingAppWithoutLosingPreferencesAndWithoutNewPrivileges() throws Exception {
        Path oldArchive = sandbox.resolve("old.ncapp");
        Path updatedArchive = sandbox.resolve("new.ncapp");
        createPackage(oldArchive, "aula.app", "0.4.1", "network.connect,credentials.access");
        createPackage(updatedArchive, "aula.app", "0.4.2", "credentials.access,agenda.write");
        AppRegistry registry = new AppRegistry(sandbox.resolve("upgrade.db"));
        registry.registerJar(oldArchive);
        registry.setDecision("aula.app", "network.connect", true);
        registry.setDecision("aula.app", "credentials.access", true);
        registry.setStartupMode("aula.app", AppRegistry.StartupMode.DELAYED);
        String originalDate = registry.listApps().get(0).installedAt();

        registry.updateJar("aula.app", updatedArchive);
        AppRegistry.AppRecord updated = registry.listApps().get(0);
        assertEquals("new.ncapp", updated.jarFilename());
        assertEquals("0.4.2", updated.version());
        assertEquals(originalDate, updated.installedAt());
        assertEquals(AppRegistry.StartupMode.DELAYED, updated.startupMode());
        assertEquals(java.util.Set.of("credentials.access"), registry.grantedPermissions("aula.app"));
        assertNull(registry.storedDecision("aula.app", "network.connect"));
        assertNull(registry.storedDecision("aula.app", "agenda.write"));
        assertEquals(java.util.Set.of("credentials.access", "agenda.write"),
                registry.declaredPermissions("aula.app"));

        // Updating to a version that declares the previously removed permission
        // must NOT resurrect the previously granted network permission.
        registry.updateJar("aula.app", oldArchive);
        assertNull(registry.storedDecision("aula.app", "network.connect"));
    }

    @Test
    void rejectedUpgradeLeavesRegisteredPackageUntouched() throws Exception {
        Path original = sandbox.resolve("first.ncapp");
        Path unrelated = sandbox.resolve("second.ncapp");
        createPackage(original, "aula.app", "0.4.1", "network.connect");
        createPackage(unrelated, "different.app", "1.0", "network.connect");
        AppRegistry registry = new AppRegistry(sandbox.resolve("rollback.db"));
        registry.registerJar(original);
        registry.setStartupMode("aula.app", AppRegistry.StartupMode.YES);
        assertThrows(IOException.class, () -> registry.updateJar("aula.app", unrelated));
        assertEquals("first.ncapp", registry.listApps().get(0).jarFilename());
        assertEquals(AppRegistry.StartupMode.YES, registry.listApps().get(0).startupMode());
    }

    private static void createPackage(Path file, String id, String version, String permissions)
            throws IOException {
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("NeuroCenter-App-Id", id);
        attrs.putValue("NeuroCenter-App-Desktop", "true");
        attrs.putValue("Implementation-Title", "Aula Demo");
        attrs.putValue("Implementation-Version", version);
        attrs.putValue("NeuroCenter-App-Permissions", permissions);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file), mf)) {
            jar.putNextEntry(new JarEntry("META-INF/services/com.neurocenter.api.NeuroApp"));
            jar.write("com.test.StandaloneApp\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @Test
    void uninstallIsTransactionalAndNewInstallationCanUseSameIdImmediately() throws Exception {
        Path first = sandbox.resolve("first.ncapp");
        Path second = sandbox.resolve("second.ncapp");
        createPackage(first, "aula.app", "0.4.1", "network.connect");
        createPackage(second, "aula.app", "0.4.2", "credentials.access");
        AppRegistry registry = new AppRegistry(sandbox.resolve("uninstall.db"));
        registry.registerJar(first);
        registry.setStartupMode("aula.app", AppRegistry.StartupMode.DELAYED);
        registry.setDecision("aula.app", "network.connect", true);

        registry.removeByFilename("first.ncapp");
        assertTrue(registry.listApps().isEmpty());
        assertEquals(java.util.Set.of(), registry.grantedPermissions("aula.app"));
        assertThrows(java.sql.SQLException.class,
                () -> registry.removeByFilename("first.ncapp"));

        registry.registerJar(second);
        assertEquals("second.ncapp", registry.listApps().get(0).jarFilename());
        assertEquals(AppRegistry.StartupMode.NO, registry.listApps().get(0).startupMode());
        assertNull(registry.storedDecision("aula.app", "credentials.access"));
    }

    @Test
    void rejectsLegacyJarExtension() throws Exception {
        Path archive = sandbox.resolve("legacy.jar");
        Files.writeString(archive, "not an application");
        AppRegistry registry = new AppRegistry(sandbox.resolve("apps.db"));
        assertThrows(IOException.class, () -> registry.registerJar(archive));
    }
}
