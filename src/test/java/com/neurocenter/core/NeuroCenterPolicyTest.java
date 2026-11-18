package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilePermission;
import java.net.SocketPermission;
import java.net.URL;
import java.security.CodeSource;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeuroCenterPolicyTest {

    @TempDir
    PathFixture temp;

    @Test
    void externalAppGetsOnlyItsOwnJarAndPrivateDataDirectory() throws Exception {
        var paths = temp.create();
        NeuroCenterSecurity.NeuroCenterPolicy policy = newPolicy(paths.hostJar(), paths.apps());
        policy.registerApp(paths.appJar(), paths.data(), Set.of());

        ProtectionDomain app = domain(paths.appJar().toUri().toURL());

        assertTrue(policy.implies(app, new FilePermission(paths.appJar().toString(), "read")));
        assertTrue(policy.implies(app,
                new FilePermission(paths.data().resolve("settings.properties").toString(), "read")));
        assertTrue(policy.implies(app,
                new FilePermission(paths.data().resolve("cache/item.bin").toString(), "write")));

        assertFalse(policy.implies(app,
                new FilePermission(paths.otherFile().toString(), "read")));
        assertFalse(policy.implies(app,
                new FilePermission(paths.otherFile().toString(), "write")));
        assertFalse(policy.implies(app,
                new SocketPermission("example.com:443", "connect")));
        assertFalse(policy.implies(app, new RuntimePermission("createClassLoader")));
    }

    @Test
    void additionalPermissionsAreGrantedOnlyWhenExplicitlySelected() throws Exception {
        var paths = temp.create();
        NeuroCenterSecurity.NeuroCenterPolicy policy = newPolicy(paths.hostJar(), paths.apps());
        policy.registerApp(paths.appJar(), paths.data(), Set.of("network.connect"));

        ProtectionDomain app = domain(paths.appJar().toUri().toURL());

        assertTrue(policy.implies(app,
                new SocketPermission("example.com:443", "connect,resolve")));
        assertFalse(policy.implies(app,
                new SocketPermission("example.com:443", "listen")));
        assertFalse(policy.implies(app,
                new FilePermission(paths.otherFile().toString(), "read")));
    }

    @Test
    void trustedHostKeepsItsPermissionsButUnregisteredAppJarsAreDenied() throws Exception {
        var paths = temp.create();
        NeuroCenterSecurity.NeuroCenterPolicy policy = newPolicy(paths.hostJar(), paths.apps());

        ProtectionDomain host = domain(paths.hostJar().toUri().toURL());
        ProtectionDomain unregistered = domain(paths.appJar().toUri().toURL());

        assertTrue(policy.implies(host, new FilePermission("<<ALL FILES>>", "read,write,delete")));
        assertTrue(policy.implies(host, new SocketPermission("*", "connect,listen,accept,resolve")));
        assertFalse(policy.implies(unregistered, new FilePermission("<<ALL FILES>>", "read")));
    }

    private static NeuroCenterSecurity.NeuroCenterPolicy newPolicy(java.nio.file.Path hostJar,
                                                                    java.nio.file.Path apps) throws Exception {
        return new NeuroCenterSecurity.NeuroCenterPolicy(
                Policy.getPolicy(), hostJar.toUri().toURL(), apps);
    }

    private static ProtectionDomain domain(URL location) {
        CodeSource source = new CodeSource(location, (java.security.cert.Certificate[]) null);
        return new ProtectionDomain(source, null);
    }

    private static final class PathFixture {
        private record Paths(java.nio.file.Path hostJar, java.nio.file.Path apps,
                             java.nio.file.Path appJar, java.nio.file.Path data,
                             java.nio.file.Path otherFile) {}

        Paths create() throws Exception {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("neurocenter-security-test");
            java.nio.file.Path apps = java.nio.file.Files.createDirectories(root.resolve("apps"));
            java.nio.file.Path appJar = java.nio.file.Files.writeString(apps.resolve("external.jar"), "test-jar");
            java.nio.file.Path data = java.nio.file.Files.createDirectories(root.resolve("data").resolve("example"));
            java.nio.file.Path otherFile = java.nio.file.Files.writeString(root.resolve("secret.txt"), "secret");
            java.nio.file.Path hostJar = java.nio.file.Files.writeString(root.resolve("host.jar"), "host");
            return new Paths(hostJar, apps, appJar, data, otherFile);
        }
    }
}
