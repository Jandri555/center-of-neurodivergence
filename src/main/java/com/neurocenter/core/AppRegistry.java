package com.neurocenter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Registro SQLite de las aplicaciones externas instaladas.
 * La base de datos es la fuente de verdad; la carpeta apps/ solo almacena los JAR.
 */
public final class AppRegistry {

    private final String jdbcUrl;

    public record AppRecord(
            String id,
            String name,
            String author,
            String version,
            String jarFilename,
            String installedAt,
            long sizeBytes,
            boolean autorun) {
        @Override
        public String toString() {
            return name + (autorun ? " — inicio automático: sí" : " — inicio automático: no");
        }
    }

    public AppRegistry(Path databaseFile) throws SQLException, IOException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        initialize();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS installed_apps (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        author TEXT NOT NULL,
                        version TEXT NOT NULL,
                        jar_filename TEXT NOT NULL UNIQUE,
                        installed_at TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        autorun INTEGER NOT NULL DEFAULT 0 CHECK (autorun IN (0, 1))
                    )
                    """);
        }
    }

    /** Lee los metadatos del manifiesto y registra un JAR recién instalado. */
    public void registerJar(Path jar) throws IOException, SQLException {
        String filename = jar.getFileName().toString();
        String fallback = filename.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? filename.substring(0, filename.length() - 4) : filename;

        String id = safeId(fallback);
        String name = fallback;
        String author = "Desconocido";
        String version = "Desconocida";

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            if (jarFile.getJarEntry("META-INF/services/com.neurocenter.api.NeuroApp") == null) {
                throw new IOException("El JAR no declara ninguna app NeuroApp. Falta META-INF/services/com.neurocenter.api.NeuroApp.");
            }
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                id = safeId(value(attributes, "NeuroCenter-App-Id", id));
                name = value(attributes, Attributes.Name.IMPLEMENTATION_TITLE, name);
                author = value(attributes, Attributes.Name.IMPLEMENTATION_VENDOR,
                        value(attributes, Attributes.Name.SPECIFICATION_VENDOR,
                                value(attributes, "Author", author)));
                version = value(attributes, Attributes.Name.IMPLEMENTATION_VERSION, version);
            }
        }

        if (id.isBlank()) throw new IOException("El JAR no proporciona un identificador de aplicación válido.");
        if (name.isBlank()) name = fallback;

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO installed_apps
                     (id, name, author, version, jar_filename, installed_at, size_bytes, autorun)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, author);
            statement.setString(4, version);
            statement.setString(5, filename);
            statement.setString(6, Instant.now().toString());
            statement.setLong(7, Files.size(jar));
            statement.executeUpdate();
        }
    }

    public List<AppRecord> listApps() throws SQLException {
        List<AppRecord> result = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, name, author, version, jar_filename, installed_at, size_bytes, autorun
                     FROM installed_apps ORDER BY name COLLATE NOCASE
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new AppRecord(
                        rows.getString("id"),
                        rows.getString("name"),
                        rows.getString("author"),
                        rows.getString("version"),
                        rows.getString("jar_filename"),
                        rows.getString("installed_at"),
                        rows.getLong("size_bytes"),
                        rows.getInt("autorun") == 1));
            }
        }
        return result;
    }

    public List<String> installedJarFilenames() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT jar_filename FROM installed_apps ORDER BY jar_filename");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    public List<String> autorunJarFilenames() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT jar_filename FROM installed_apps WHERE autorun = 1 ORDER BY name COLLATE NOCASE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    public void setAutorun(String id, boolean autorun) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE installed_apps SET autorun = ? WHERE id = ?")) {
            statement.setInt(1, autorun ? 1 : 0);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    public void removeByFilename(String filename) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM installed_apps WHERE jar_filename = ?")) {
            statement.setString(1, filename);
            statement.executeUpdate();
        }
    }

    private static String value(Attributes attributes, Attributes.Name key, String fallback) {
        String value = attributes.getValue(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String value(Attributes attributes, String key, String fallback) {
        String value = attributes.getValue(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return id.isBlank() ? "external-app" : id;
    }
}
