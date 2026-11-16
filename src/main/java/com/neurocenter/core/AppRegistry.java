package com.neurocenter.core;

import com.google.gson.Gson;

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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Registro SQLite de las aplicaciones externas instaladas y de sus permisos.
 * La base de datos es la fuente de verdad; la carpeta apps/ solo almacena los JAR.
 */
public final class AppRegistry {

    public static final String STORAGE_PERMISSION = "storage";
    public static final String ALLOW_ALWAYS = "ALLOW_ALWAYS";
    public static final String DENY_ALWAYS = "DENY_ALWAYS";
    public static final String LIMITED_FOLDERS = "LIMITED_FOLDERS";

    private static final Gson GSON = new Gson();
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

    public record PermissionRecord(
            String appId,
            String permission,
            String decision,
            List<String> folders) {}

    public record PermissionInfo(
            String appId,
            String appName,
            String permission,
            String decision,
            List<String> folders) {
        @Override
        public String toString() {
            String state = switch (decision) {
                case ALLOW_ALWAYS -> "permitido siempre";
                case DENY_ALWAYS -> "denegado permanentemente";
                case LIMITED_FOLDERS -> "solo carpetas autorizadas (" + folders.size() + ")";
                default -> decision;
            };
            return appName + " — Almacenamiento: " + state;
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS app_permissions (
                        app_id TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        decision TEXT NOT NULL CHECK (
                            decision IN ('ALLOW_ALWAYS', 'DENY_ALWAYS', 'LIMITED_FOLDERS')
                        ),
                        folders_json TEXT NOT NULL DEFAULT '[]',
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (app_id, permission)
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

    /** Guarda una decisión permanente para un permiso de una app. */
    public void savePermission(String appId, String permission, String decision, List<String> folders)
            throws SQLException {
        if (!ALLOW_ALWAYS.equals(decision) && !DENY_ALWAYS.equals(decision)
                && !LIMITED_FOLDERS.equals(decision)) {
            throw new IllegalArgumentException("Decisión de permiso no válida: " + decision);
        }
        String json = GSON.toJson(folders == null ? List.of() : folders);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO app_permissions (app_id, permission, decision, folders_json, updated_at)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(app_id, permission) DO UPDATE SET
                         decision = excluded.decision,
                         folders_json = excluded.folders_json,
                         updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, appId);
            statement.setString(2, permission);
            statement.setString(3, decision);
            statement.setString(4, json);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public PermissionRecord getPermission(String appId, String permission) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT decision, folders_json
                     FROM app_permissions WHERE app_id = ? AND permission = ?
                     """)) {
            statement.setString(1, appId);
            statement.setString(2, permission);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return new PermissionRecord(appId, permission,
                        row.getString("decision"), parseFolders(row.getString("folders_json")));
            }
        }
    }

    public List<PermissionInfo> listPermissions() throws SQLException {
        List<PermissionInfo> result = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.app_id, a.name, p.permission, p.decision, p.folders_json
                     FROM app_permissions p
                     JOIN installed_apps a ON a.id = p.app_id
                     ORDER BY a.name COLLATE NOCASE, p.permission
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new PermissionInfo(
                        rows.getString("app_id"),
                        rows.getString("name"),
                        rows.getString("permission"),
                        rows.getString("decision"),
                        parseFolders(rows.getString("folders_json"))));
            }
        }
        return result;
    }

    /** Restablece un permiso: la próxima solicitud volverá a mostrar el diálogo. */
    public void removePermission(String appId, String permission) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM app_permissions WHERE app_id = ? AND permission = ?")) {
            statement.setString(1, appId);
            statement.setString(2, permission);
            statement.executeUpdate();
        }
    }

    public void removeByFilename(String filename) throws SQLException {
        try (Connection connection = connect()) {
            try (PreparedStatement permissions = connection.prepareStatement(
                    "DELETE FROM app_permissions WHERE app_id IN "
                            + "(SELECT id FROM installed_apps WHERE jar_filename = ?)")) {
                permissions.setString(1, filename);
                permissions.executeUpdate();
            }
            try (PreparedStatement app = connection.prepareStatement(
                    "DELETE FROM installed_apps WHERE jar_filename = ?")) {
                app.setString(1, filename);
                app.executeUpdate();
            }
        }
    }

    private static List<String> parseFolders(String json) {
        try {
            String[] folders = GSON.fromJson(json, String[].class);
            return folders == null ? List.of() : Arrays.asList(folders);
        } catch (RuntimeException ex) {
            return List.of();
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
