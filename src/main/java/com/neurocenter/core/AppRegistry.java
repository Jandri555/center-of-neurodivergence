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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Registro SQLite de las aplicaciones externas instaladas.
 * La base de datos es la fuente de verdad; la carpeta apps/ solo almacena los JAR.
 */
public final class AppRegistry {

    private final String jdbcUrl;

    public record AppSecurityProfile(String appId, Set<String> grantedPermissions,
                                     Set<String> declaredPermissions) {
        public AppSecurityProfile(String id, Set<String> grants) { this(id, grants, Set.of()); }
    }

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
                        autorun INTEGER NOT NULL DEFAULT 0 CHECK (autorun IN (0, 1)),
                        declared_permissions TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS app_permissions (
                        app_id TEXT NOT NULL,
                        permission_id TEXT NOT NULL,
                        granted INTEGER NOT NULL DEFAULT 0 CHECK (granted IN (0, 1)),
                        PRIMARY KEY (app_id, permission_id)
                    )
                    """);
            // Existing SQLite installations need a non-destructive schema migration.
            try {
                statement.executeUpdate("ALTER TABLE installed_apps ADD COLUMN declared_permissions TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ex) {
                if (ex.getMessage() == null || !ex.getMessage().contains("duplicate column"))
                    throw ex;
            }
        }
    }

    /** Lee los metadatos del manifiesto y registra un JAR recién instalado. */
    public void registerJar(Path jar) throws IOException, SQLException {
        String filename = jar.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".ncapp")) {
            throw new IOException("NeuroCenter solo admite aplicaciones .ncapp.");
        }
        String fallback = filename.toLowerCase(Locale.ROOT).endsWith(".ncapp")
                ? filename.substring(0, filename.length() - 6) : filename;

        String id = safeId(fallback);
        String name = fallback;
        String author = "Desconocido";
        String version = "Desconocida";
        Set<String> declared = Set.of();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            Attributes attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();
            id = safeId(value(attributes, "NeuroCenter-App-Id", id));
            declared = AppPermissionManifest.parse(manifest);
            name = value(attributes, Attributes.Name.IMPLEMENTATION_TITLE, name);
            author = value(attributes, Attributes.Name.IMPLEMENTATION_VENDOR,
                    value(attributes, Attributes.Name.SPECIFICATION_VENDOR,
                            value(attributes, "Author", author)));
            version = value(attributes, Attributes.Name.IMPLEMENTATION_VERSION, version);

            // Comprobar primero la plataforma para que una app móvil no falle
            // con un mensaje engañoso por no incluir el proveedor Swing.
            PlatformCompatibility.fromAppManifest(manifest)
                    .require(com.neurocenter.api.NeuroPlatform.DESKTOP, "la app «" + name + "»");

            if (jarFile.getJarEntry("META-INF/services/com.neurocenter.api.NeuroApp") == null) {
                throw new IOException("La app es compatible con escritorio, pero no declara ningún proveedor "
                        + "NeuroApp. Falta META-INF/services/com.neurocenter.api.NeuroApp.");
            }
        }

        if (id.isBlank()) throw new IOException("El JAR no proporciona un identificador de aplicación válido.");
        if (name.isBlank()) name = fallback;

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO installed_apps
                     (id, name, author, version, jar_filename, installed_at, size_bytes, autorun, declared_permissions)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, author);
            statement.setString(4, version);
            statement.setString(5, filename);
            statement.setString(6, Instant.now().toString());
            statement.setLong(7, Files.size(jar));
            statement.setString(8, AppPermissionManifest.serialize(declared));
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

    /**
     * Devuelve los perfiles de seguridad indexados por nombre de JAR.
     * Las aplicaciones recién instaladas no tienen permisos adicionales concedidos.
     */
    public Map<String, AppSecurityProfile> securityProfilesByJar() throws SQLException {
        Map<String, AppSecurityProfile> result = new LinkedHashMap<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, jar_filename, declared_permissions FROM installed_apps ORDER BY jar_filename");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String appId = rows.getString("id");
                String jarFilename = rows.getString("jar_filename");
                result.put(jarFilename,
                        new AppSecurityProfile(appId, grantedPermissions(connection, appId),
                           AppPermissionManifest.deserialize(rows.getString("declared_permissions"))));
            }
        }
        return result;
    }

    public Set<String> grantedPermissions(String appId) throws SQLException {
        try (Connection connection = connect()) {
            return grantedPermissions(connection, appId);
        }
    }

    private Set<String> grantedPermissions(Connection connection, String appId) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT permission_id FROM app_permissions WHERE app_id = ? AND granted = 1")) {
            statement.setString(1, appId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        result.retainAll(declaredPermissions(connection, appId));
        return Set.copyOf(result);
    }

    public Set<String> declaredPermissions(String appId) throws SQLException {
        try (Connection conn = connect()) { return declaredPermissions(conn, appId); }
    }

    private Set<String> declaredPermissions(Connection conn, String appId) throws SQLException {
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT declared_permissions FROM installed_apps WHERE id=?")) {
            q.setString(1, appId);
            try (ResultSet rows = q.executeQuery()) {
                return rows.next() ? AppPermissionManifest.deserialize(rows.getString(1)) : Set.of();
            }
        }
    }

    public Boolean storedDecision(String appId, String permission) throws SQLException {
        if (!declaredPermissions(appId).contains(permission)) return null;
        try (Connection conn = connect(); PreparedStatement q = conn.prepareStatement(
                "SELECT granted FROM app_permissions WHERE app_id=? AND permission_id=?")) {
            q.setString(1, appId); q.setString(2, permission);
            try (ResultSet rows = q.executeQuery()) {
                return rows.next() ? rows.getInt(1) == 1 : null;
            }
        }
    }

    public void setDecision(String appId, String permission, Boolean allowed) throws SQLException {
        if (!declaredPermissions(appId).contains(permission))
            throw new SecurityException("Permiso no declarado: " + permission);
        try (Connection conn = connect()) {
            if (allowed == null) {
                try (PreparedStatement q = conn.prepareStatement(
                        "DELETE FROM app_permissions WHERE app_id=? AND permission_id=?")) {
                    q.setString(1, appId); q.setString(2, permission); q.executeUpdate();
                }
            } else {
                try (PreparedStatement q = conn.prepareStatement(
                        "INSERT OR REPLACE INTO app_permissions(app_id,permission_id,granted) VALUES(?,?,?)")) {
                    q.setString(1,appId); q.setString(2,permission);
                    q.setInt(3, allowed ? 1 : 0); q.executeUpdate();
                }
            }
        }
    }

    /** Sustituye el conjunto de permisos concedidos a una aplicación. */
    public void setGrantedPermissions(String appId, Set<String> permissionIds) throws SQLException {
        Set<String> validated = new LinkedHashSet<>();
        if (permissionIds != null) {
            for (String permissionId : permissionIds) {
                if (!NeuroCenterSecurity.isSupportedPermission(permissionId)) {
                    throw new IllegalArgumentException("Permiso no reconocido: " + permissionId);
                }
                if (!declaredPermissions(appId).contains(permissionId))
                    throw new SecurityException("Permiso no declarado: " + permissionId);
                validated.add(permissionId);
            }
        }

        try (Connection connection = connect()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM app_permissions WHERE app_id = ?")) {
                    delete.setString(1, appId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO app_permissions (app_id, permission_id, granted)
                        VALUES (?, ?, 1)
                        """)) {
                    for (String permissionId : validated) {
                        insert.setString(1, appId);
                        insert.setString(2, permissionId);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
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
