package com.neurocenter.core;

import android.content.Context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Estructura de datos de Center of Neurodivergence en Android.
 *
 * El directorio raíz es privado de la aplicación, igual que AppData en
 * escritorio en cuanto a organización: contiene apps/, themes/ y data/.
 * Las aplicaciones guardan sus propios datos en data/<appId>/.
 */
public final class AppPaths {

    public static final String APP_FOLDER = "CenterOfNeurodivergence";

    private AppPaths() {}

    public static Path root(Context context) {
        return context.getFilesDir().toPath().resolve(APP_FOLDER);
    }

    public static Path appsDir(Context context) {
        return root(context).resolve("apps");
    }

    public static Path themesDir(Context context) {
        return root(context).resolve("themes");
    }

    public static Path dataDir(Context context) {
        return root(context).resolve("data");
    }

    /** Ubicación del registro SQLite de aplicaciones, para el futuro gestor. */
    public static Path appsDatabase(Context context) {
        return root(context).resolve("apps.db");
    }

    /** Carpeta de datos de una app; se crea cuando se solicita. */
    public static Path appDataDir(Context context, String appId) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("El identificador de la app no puede estar vacío.");
        }
        String safe = appId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.trim().isEmpty() || safe.chars().allMatch(c -> c == '.')) safe = "_";

        Path directory = dataDir(context).resolve(safe);
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo crear la carpeta de datos de " + safe, ex);
        }
        return directory;
    }

    /** Prepara el esqueleto común aunque todavía no haya apps ni temas externos. */
    public static void ensureDirectories(Context context) {
        try {
            Files.createDirectories(appsDir(context));
            Files.createDirectories(themesDir(context));
            Files.createDirectories(dataDir(context));
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "No se pudo preparar la carpeta de datos en " + root(context), ex);
        }
    }
}
