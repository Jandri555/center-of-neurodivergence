package com.neurocenter.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Almacén clave/valor muy simple, guardado en un archivo .properties (UTF-8). */
public final class PropertiesStore {

    private final Path file;
    private final Properties props = new Properties();

    public PropertiesStore(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("No se pudo leer " + file + ": " + e);
            }
        }
    }

    public synchronized String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public synchronized void put(String key, String value) {
        props.setProperty(key, value);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, null);
            }
        } catch (IOException e) {
            System.err.println("No se pudo guardar " + file + ": " + e);
        }
    }
}
