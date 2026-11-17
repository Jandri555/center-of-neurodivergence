package com.neurocenter.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class PlatformCompatibilityTest {

    @Test
    void legacyAppManifestRemainsDesktopCompatible() throws IOException {
        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(null);

        assertTrue(targets.desktop());
        assertFalse(targets.mobile());
    }

    @Test
    void appCanDeclareBothPlatforms() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "true");
        attributes.putValue("NeuroCenter-App-Mobile", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);

        assertTrue(targets.supports(PlatformCompatibility.Platform.DESKTOP));
        assertTrue(targets.supports(PlatformCompatibility.Platform.MOBILE));
    }

    @Test
    void desktopRejectsMobileOnlyAppWithClearMessage() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "false");
        attributes.putValue("NeuroCenter-App-Mobile", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);
        IOException error = assertThrows(IOException.class,
                () -> targets.require(PlatformCompatibility.Platform.DESKTOP, "la app «Solo móvil»"));

        assertTrue(error.getMessage().contains("Este dispositivo (escritorio) no es compatible"));
        assertTrue(error.getMessage().contains("móvil"));
    }

    @Test
    void legacyThemeRemainsDesktopCompatible() throws IOException {
        var json = JsonParser.parseString("""
                {"id":"legacy","name":"Tema antiguo","base":"dark"}
                """).getAsJsonObject();

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromThemeManifest(json);

        assertTrue(targets.desktop());
        assertFalse(targets.mobile());
    }

    @Test
    void mobileOnlyThemeIsRejectedOnDesktop() throws IOException {
        var json = JsonParser.parseString("""
                {
                  "id":"mobile-theme",
                  "name":"Tema móvil",
                  "compatibility":{"desktop":false,"mobile":true}
                }
                """).getAsJsonObject();

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromThemeManifest(json);
        IOException error = assertThrows(IOException.class,
                () -> targets.require(PlatformCompatibility.Platform.DESKTOP, "el tema «Tema móvil»"));

        assertTrue(error.getMessage().contains("Este dispositivo (escritorio) no es compatible"));
        assertTrue(error.getMessage().contains("móvil"));
    }

    @Test
    void compatibilityManifestMustDeclareBothBooleanFlags() {
        var json = JsonParser.parseString("""
                {"compatibility":{"desktop":true}}
                """).getAsJsonObject();

        assertThrows(IOException.class, () -> PlatformCompatibility.fromThemeManifest(json));
    }

    @Test
    void manifestCannotDisableBothPlatforms() {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "false");
        attributes.putValue("NeuroCenter-App-Mobile", "false");

        assertThrows(IOException.class, () -> PlatformCompatibility.fromAppManifest(manifest));
    }
}
