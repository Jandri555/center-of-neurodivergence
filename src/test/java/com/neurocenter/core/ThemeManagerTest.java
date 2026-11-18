package com.neurocenter.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void builtInDarkThemeUsesDeepCharcoalBackground() {
        ThemeManager.ThemeInfo dark = ThemeManager.availableThemes().stream()
                .filter(theme -> "dark".equals(theme.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("#181818", dark.colors().get("background").getAsString());
        assertEquals("#202020", dark.colors().get("surface").getAsString());
        assertEquals("#292929", dark.colors().get("button").getAsString());
    }

    @Test
    void builtInThemesDoNotDefineCustomImageAssets() {
        ThemeManager.ThemeInfo light = ThemeManager.availableThemes().stream()
                .filter(theme -> "light".equals(theme.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(light.centerIcon() == null);
        assertTrue(light.background() == null);
    }

    @Test
    void validatesPngBackgroundAndCenterIconResources() throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("resources"));
        BufferedImage png = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(png, "png", resources.resolve("background.png").toFile());
        ImageIO.write(png, "png", resources.resolve("center-icon.png").toFile());

        JsonObject manifest = new JsonObject();
        JsonObject background = new JsonObject();
        background.addProperty("image", "resources/background.png");
        background.addProperty("darkening", 35);
        background.addProperty("transparency", 20);
        manifest.add("background", background);
        manifest.addProperty("centerIcon", "resources/center-icon.png");

        assertDoesNotThrow(() -> ThemeManager.validateAssets(tempDir, manifest));
    }

    @Test
    void rejectsThemeImagesEscapingResourcesDirectory() {
        JsonObject manifest = new JsonObject();
        JsonObject background = new JsonObject();
        background.addProperty("image", "resources/../../outside.png");
        manifest.add("background", background);

        assertThrows(IOException.class, () -> ThemeManager.validateAssets(tempDir, manifest));
    }

    @Test
    void rejectsBackgroundPercentagesOutsideZeroToOneHundred() throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("resources"));
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
                "png", resources.resolve("background.png").toFile());

        JsonObject manifest = new JsonObject();
        JsonObject background = new JsonObject();
        background.addProperty("image", "resources/background.png");
        background.addProperty("darkening", 101);
        manifest.add("background", background);

        assertThrows(IOException.class, () -> ThemeManager.validateAssets(tempDir, manifest));
    }
}
