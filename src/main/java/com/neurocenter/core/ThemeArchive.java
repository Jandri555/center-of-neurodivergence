package com.neurocenter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Instalación segura de paquetes .tme: ZIP con theme.json y resources/. */
public final class ThemeArchive {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_ENTRIES = 200;
    private static final Gson GSON = new Gson();

    private ThemeArchive() {}

    public static String install(Path archive) throws IOException {
        if (archive == null || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tme"))
            throw new IOException("Selecciona un archivo .tme.");
        if (!Files.isRegularFile(archive)) throw new IOException("El archivo no existe.");

        Path root = AppPaths.themesDir();
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, ".install-");
        boolean moved = false;
        try {
            long total = 0;
            int count = 0;
            boolean manifestFound = false;
            try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > MAX_ENTRIES) throw new IOException("El tema contiene demasiados archivos.");
                    String name = entry.getName().replace('\\', '/');
                    validateName(name);
                    if (entry.isDirectory()) {
                        if (!name.equals("resources/") && !name.startsWith("resources/"))
                            throw new IOException("Carpeta no permitida: " + name);
                        Files.createDirectories(temp.resolve(name).normalize());
                        continue;
                    }
                    if (!name.equals("theme.json") && !name.startsWith("resources/"))
                        throw new IOException("Solo se permiten theme.json y archivos dentro de resources/.");
                    if (name.equals("theme.json")) manifestFound = true;
                    Path target = temp.resolve(name).normalize();
                    if (!target.startsWith(temp)) throw new IOException("Ruta insegura en el paquete.");
                    if (Files.exists(target)) throw new IOException("El paquete contiene una entrada duplicada: " + name);
                    Files.createDirectories(target.getParent());
                    try (var out = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_BYTES) throw new IOException("El tema supera 20 MB descomprimidos.");
                            out.write(buffer, 0, n);
                        }
                    }
                    zip.closeEntry();
                }
            }
            if (!manifestFound) throw new IOException("Falta theme.json.");
            JsonObject json;
            try (var reader = Files.newBufferedReader(temp.resolve("theme.json"))) {
                json = GSON.fromJson(reader, JsonObject.class);
            } catch (RuntimeException ex) {
                throw new IOException("theme.json no contiene un JSON válido.", ex);
            }
            if (!isString(json, "id"))
                throw new IOException("El manifiesto debe incluir un identificador «id» de texto.");
            String id = json.get("id").getAsString();
            if (!id.matches("[A-Za-z0-9._-]{1,48}") || id.equals("light") || id.equals("dark"))
                throw new IOException("Identificador no válido o reservado.");
            if (!isString(json, "name") || json.get("name").getAsString().isBlank())
                throw new IOException("El manifiesto debe incluir «name» de texto.");
            String base = isString(json, "base") ? json.get("base").getAsString() : "";
            if (!base.equals("light") && !base.equals("dark"))
                throw new IOException("El campo «base» debe ser «light» o «dark».");

            Path destination = root.resolve(id);
            if (Files.exists(destination)) throw new IOException("Ya existe un tema con el identificador «" + id + "».");
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, destination);
            }
            moved = true;
            ThemeManager.refreshThemes();
            return id;
        } finally {
            if (!moved) deleteTree(temp);
        }
    }

    public static void uninstall(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,48}") || id.equals("light") || id.equals("dark"))
            throw new IOException("No se puede desinstalar ese tema.");
        Path root = AppPaths.themesDir().toAbsolutePath().normalize();
        Path target = root.resolve(id).normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target)) throw new IOException("No se encuentra el tema.");
        deleteTree(target);
        ThemeManager.refreshThemes();
    }

    private static boolean isString(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonPrimitive()
                && json.get(key).getAsJsonPrimitive().isString();
    }

    private static void validateName(String name) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains(":") || name.matches("^[A-Za-z]:.*"))
            throw new IOException("Ruta no permitida en el paquete.");
        for (String part : name.split("/"))
            if (part.equals(".") || part.equals("..")) throw new IOException("Ruta insegura en el paquete.");
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
}
