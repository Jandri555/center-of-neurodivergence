package com.neurocenter.core;

import com.google.gson.JsonParser;
import com.neurocenter.api.NeuroPlatform;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class PlatformCompatibilityTest {

    @Test
    void missingAppManifestFlagsMeanFalse() throws IOException {
        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(null);

        assertFalse(targets.desktop());
        assertFalse(targets.mobile());
        assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.DESKTOP, "la app"));
    }

    @Test
    void missingMobileFlagDefaultsToFalse() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("NeuroCenter-App-Desktop", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);

        assertTrue(targets.desktop());
        assertFalse(targets.mobile());
        assertTrue(targets.supports(NeuroPlatform.DESKTOP));
        assertFalse(targets.supports(NeuroPlatform.MOBILE));
    }

    @Test
    void missingDesktopFlagDefaultsToFalse() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("NeuroCenter-App-Mobile", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);

        assertFalse(targets.desktop());
        assertTrue(targets.mobile());
        assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.DESKTOP, "la app «Solo móvil»"));
    }

    @Test
    void appCanDeclareBothPlatforms() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "true");
        attributes.putValue("NeuroCenter-App-Mobile", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);

        assertTrue(targets.supports(NeuroPlatform.DESKTOP));
        assertTrue(targets.supports(NeuroPlatform.MOBILE));
    }

    @Test
    void appWithBothFlagsFalseSupportsNoPlatform() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "false");
        attributes.putValue("NeuroCenter-App-Mobile", "false");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);

        assertFalse(targets.desktop());
        assertFalse(targets.mobile());
        assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.DESKTOP, "la app"));
        assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.MOBILE, "la app"));
    }

    @Test
    void desktopRejectsMobileOnlyAppWithClearMessage() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("NeuroCenter-App-Desktop", "false");
        attributes.putValue("NeuroCenter-App-Mobile", "true");

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromAppManifest(manifest);
        IOException error = assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.DESKTOP, "la app «Solo móvil»"));

        assertTrue(error.getMessage().contains("Este dispositivo (escritorio) no es compatible"));
        assertTrue(error.getMessage().contains("móvil"));
    }

    @Test
    void missingThemeCompatibilityFlagsMeanFalse() throws IOException {
        var json = JsonParser.parseString("""
                {"id":"sin-flags","name":"Tema sin flags","base":"dark"}
                """).getAsJsonObject();

        PlatformCompatibility.Targets targets = PlatformCompatibility.fromThemeManifest(json);

        assertFalse(targets.desktop());
        assertFalse(targets.mobile());
        assertThrows(IOException.class,
                () -> targets.require(NeuroPlatform.DESKTOP, "el tema «Tema sin flags»"));
    }

    @Test
    void missingMobileThemeFlagDefaultsToFalse() throws IOException {
        var json = JsonParser.parseString("""
                {
                  "compatibility":{"desktop":true}
                }
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
                () -> targets.require(NeuroPlatform.DESKTOP, "el tema «Tema móvil»"));

        assertTrue(error.getMessage().contains("Este dispositivo (escritorio) no es compatible"));
        assertTrue(error.getMessage().contains("móvil"));
    }

    @Test
    void invalidThemeFlagTypeIsRejected() {
        var json = JsonParser.parseString("""
                {"compatibility":{"desktop":"true","mobile":false}}
                """).getAsJsonObject();

        assertThrows(IOException.class, () -> PlatformCompatibility.fromThemeManifest(json));
    }

    @Test
    void invalidAppFlagValueIsRejected() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("NeuroCenter-App-Desktop", "yes");

        assertThrows(IOException.class, () -> PlatformCompatibility.fromAppManifest(manifest));
    }
}
