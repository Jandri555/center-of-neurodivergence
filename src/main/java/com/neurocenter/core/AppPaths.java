package com.neurocenter.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Carpetas de la aplicación.
 * Windows: %APPDATA%\CenterOfNeurodivergence   (plugins\ y data\ dentro)
 */
public final class AppPaths {

    /** Nombre de la carpeta en AppData (sin espacios). */
    public static final String APP_FOLDER = "CenterOfNeurodivergence";

    private static final Path ROOT = resolveRoot();

    private AppPaths() {}

    public static Path root() { return ROOT; }

    public static Path pluginsDir() { return ROOT.resolve("plugins"); }

    public static Path dataDir() { return ROOT.resolve("data"); }

    /** Carpeta de datos de un programa; se crea si no existe. */
    public static Path programDataDir(String programId) {
        String safe = programId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.chars().allMatch(c -> c == '.')) safe = "_";
        Path dir = dataDir().resolve(safe);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear " + dir, e);
        }
        return dir;
    }

    public static void ensureDirectories() {
        try {
            Files.createDirectories(pluginsDir());
            Files.createDirectories(dataDir());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta de datos en " + ROOT, e);
        }
    }

    private static Path resolveRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(home, "AppData", "Roaming");
        } else if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".local", "share");
        }
        return base.resolve(APP_FOLDER);
    }
}
