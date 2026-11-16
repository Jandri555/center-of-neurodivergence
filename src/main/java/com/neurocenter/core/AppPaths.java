package com.neurocenter.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Carpetas de la aplicación.
 * Windows: %APPDATA%\CenterOfNeurodivergence
 */
public final class AppPaths {

    /** Nombre de la carpeta en AppData (sin espacios). */
    public static final String APP_FOLDER = "CenterOfNeurodivergence";
    private static final String LEGACY_MIGRATION_MARKER = ".plugins-migrated-v1";

    private static final Path ROOT = resolveRoot();

    private AppPaths() {}

    public static Path root() { return ROOT; }

    public static Path appsDir() { return ROOT.resolve("apps"); }

    public static Path themesDir() { return ROOT.resolve("themes"); }

    public static Path dataDir() { return ROOT.resolve("data"); }

    /** Carpeta de datos de una app; se crea si no existe. */
    public static Path appDataDir(String appId) {
        String safe = appId.replaceAll("[^A-Za-z0-9._-]", "_");
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
            Files.createDirectories(appsDir());
            Files.createDirectories(themesDir());
            Files.createDirectories(dataDir());

            migrateLegacyPlugins();

            Path pending = ROOT.resolve("pending-uninstall.txt");
            if (Files.isRegularFile(pending)) {
                for (String name : Files.readAllLines(pending)) {
                    if (name.isBlank() || name.equals(".") || name.equals("..")) continue;
                    Path fileName = Path.of(name).getFileName();
                    if (fileName == null || !fileName.toString().equals(name)) continue;
                    Files.deleteIfExists(appsDir().resolve(name));
                }
                Files.deleteIfExists(pending);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo preparar la carpeta de datos en " + ROOT, e);
        }
    }

    /**
     * Migra los JAR antiguos una sola vez. El marcador solo se crea cuando todas las
     * copias han terminado correctamente; así, un fallo parcial se reintenta en el
     * siguiente inicio. La carpeta plugins se conserva como respaldo, pero nunca se
     * vuelve a copiar después de completar la migración.
     */
    private static void migrateLegacyPlugins() throws IOException {
        Path legacy = ROOT.resolve("plugins");
        Path marker = ROOT.resolve(LEGACY_MIGRATION_MARKER);

        if (Files.isRegularFile(marker) || !Files.isDirectory(legacy)) return;

        boolean complete = true;
        try (Stream<Path> files = Files.list(legacy)) {
            for (Path source : files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList()) {
                Path target = appsDir().resolve(source.getFileName());
                if (Files.exists(target)) continue;

                Path temporary = null;
                try {
                    temporary = Files.createTempFile(appsDir(), ".legacy-migration-", ".tmp");
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temporary, target);
                    }
                } catch (FileAlreadyExistsException ex) {
                    // Otro proceso ha instalado ya un JAR con ese nombre; no lo sobrescribimos.
                } catch (IOException ex) {
                    complete = false;
                    System.err.println("No se pudo migrar " + source + ": " + ex.getMessage());
                } finally {
                    if (temporary != null) Files.deleteIfExists(temporary);
                }
            }
        }

        if (complete) {
            Files.writeString(marker,
                    "Migración de plugins completada. La carpeta plugins se conserva como respaldo."
                            + System.lineSeparator());
        } else {
            System.err.println("La migración de plugins quedó incompleta; se reintentará en el próximo inicio.");
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
