package com.neurocenter.core;

import android.util.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Almacén clave/valor muy simple, guardado en un archivo .properties (UTF-8). Igual que el de escritorio. */
public final class PropertiesStore {

    private static final String TAG = "PropertiesStore";

    private final Path file;
    private final Properties props = new Properties();

    public PropertiesStore(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                Log.w(TAG, "No se pudo leer " + file, e);
            }
        }
    }

    public synchronized String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public synchronized void put(String key, String value) {
        props.setProperty(key, value);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, null);
            }
        } catch (IOException e) {
            Log.w(TAG, "No se pudo guardar " + file, e);
        }
    }
}
