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
 * La base de datos es la fuente de verdad; apps/ almacena paquetes .ncapp.
 */
public final class AppRegistry {

    private final String jdbcUrl;

    /** Persisted startup choice for an installed external .ncapp. */
    public enum StartupMode { NO, YES, DELAYED }

    public record PackageInfo(String id, String name, String author,
                              String version, Set<String> declaredPermissions) { }

    public record AppSecurityProfile(String appId, Set<String> grantedPermissions,
                                     Set<String> declaredPermissions) { }

    public record AppRecord(
            String id,
            String name,
            String author,
            String version,
            String jarFilename,
            String installedAt,
            long sizeBytes,
            StartupMode startupMode) {
        @Override
        public String toString() {
            // A permission selector must only identify the application.
            return name;
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
                        declared_permissions TEXT NOT NULL DEFAULT '',
                        autorun_mode TEXT NOT NULL DEFAULT 'NO'
                            CHECK (autorun_mode IN ('NO', 'YES', 'DELAYED'))
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
        }
    }

    /**
     * Validates a .ncapp before installing/updating. Identity comes exclusively
     * from the manifest, never the filename supplied by the user.
     */
    public static PackageInfo inspectPackage(Path jar) throws IOException {
        String filename = jar.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".ncapp")
                || !Files.isRegularFile(jar))
            throw new IOException("NeuroCenter solo admite archivos .ncapp válidos.");
        String fallback = filename.substring(0, filename.length() - ".ncapp".length());
        try (JarFile archive = new JarFile(jar.toFile())) {
            Manifest manifest = archive.getManifest();
            if (manifest == null)
                throw new IOException("El .ncapp no incluye META-INF/MANIFEST.MF.");
            Attributes attributes = manifest.getMainAttributes();
            String rawId = attributes.getValue("NeuroCenter-App-Id");
            if (rawId == null || rawId.isBlank())
                throw new IOException("El .ncapp no declara NeuroCenter-App-Id.");
            String id = safeId(rawId.trim());
            if (!id.equals(rawId.trim()))
                throw new IOException("NeuroCenter-App-Id no es válido: " + rawId);
            String name = value(attributes, Attributes.Name.IMPLEMENTATION_TITLE, fallback);
            String author = value(attributes, Attributes.Name.IMPLEMENTATION_VENDOR,
                    value(attributes, Attributes.Name.SPECIFICATION_VENDOR,
                            value(attributes, "Author", "Desconocido")));
            String version = value(attributes, Attributes.Name.IMPLEMENTATION_VERSION, "Desconocida");
            Set<String> declared = AppPermissionManifest.parse(manifest);
            PlatformCompatibility.fromAppManifest(manifest)
                    .require(com.neurocenter.api.NeuroPlatform.DESKTOP, "la app «" + name + "»");
            if (archive.getJarEntry("META-INF/services/com.neurocenter.api.NeuroApp") == null)
                throw new IOException("La app no declara un proveedor NeuroApp de escritorio.");
            return new PackageInfo(id, name, author, version, declared);
        }
    }

    /** Registers a newly installed .ncapp; its ID must be new. */
    public void registerJar(Path jar) throws IOException, SQLException {
        PackageInfo info = inspectPackage(jar);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO installed_apps
                     (id, name, author, version, jar_filename, installed_at, size_bytes, declared_permissions)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, info.id());
            statement.setString(2, info.name());
            statement.setString(3, info.author());
            statement.setString(4, info.version());
            statement.setString(5, jar.getFileName().toString());
            statement.setString(6, Instant.now().toString());
            statement.setLong(7, Files.size(jar));
            statement.setString(8, AppPermissionManifest.serialize(info.declaredPermissions()));
            statement.executeUpdate();
        }
    }

    /** Preserves app ID, install date, startup mode, app data, and shared grants.
     * Grants not declared in the replacement package are permanently revoked.
     * The caller stages and validates the new code before committing the change.
     */
    public void updateJar(String expectedAppId, Path newPackage) throws IOException, SQLException {
        PackageInfo info = inspectPackage(newPackage);
        if (!info.id().equals(expectedAppId))
            throw new IOException("El paquete tiene un ID distinto: " + info.id());
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE installed_apps
                        SET name = ?, author = ?, version = ?, jar_filename = ?,
                            size_bytes = ?, declared_permissions = ?
                        WHERE id = ?
                        """)) {
                    update.setString(1, info.name());
                    update.setString(2, info.author());
                    update.setString(3, info.version());
                    update.setString(4, newPackage.getFileName().toString());
                    update.setLong(5, Files.size(newPackage));
                    update.setString(6, AppPermissionManifest.serialize(info.declaredPermissions()));
                    update.setString(7, expectedAppId);
                    if (update.executeUpdate() != 1)
                        throw new SQLException("La aplicación no está instalada: " + expectedAppId);
                }
                // Revoke permissions removed from the manifest; never silently
                // restore an old decision on a subsequent upgrade.
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT permission_id FROM app_permissions WHERE app_id = ?")) {
                    select.setString(1, expectedAppId);
                    try (ResultSet rows = select.executeQuery()) {
                        List<String> revoked = new ArrayList<>();
                        while (rows.next()) {
                            String permission = rows.getString(1);
                            if (!info.declaredPermissions().contains(permission)) revoked.add(permission);
                        }
                        try (PreparedStatement delete = connection.prepareStatement(
                                "DELETE FROM app_permissions WHERE app_id = ? AND permission_id = ?")) {
                            for (String permission : revoked) {
                                delete.setString(1, expectedAppId);
                                delete.setString(2, permission);
                                delete.addBatch();
                            }
                            delete.executeBatch();
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public List<AppRecord> listApps() throws SQLException {
        List<AppRecord> result = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, name, author, version, jar_filename, installed_at, size_bytes, autorun_mode
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
                        parseStartupMode(rows.getString("autorun_mode"))));
            }
        }
        return result;
    }

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

    private static StartupMode parseStartupMode(String value) {
        try {
            return value == null ? StartupMode.NO : StartupMode.valueOf(value);
        } catch (IllegalArgumentException invalidData) {
            return StartupMode.NO;
        }
    }

    /** Choices for installed apps, keyed by .ncapp filename. */
    public Map<String, StartupMode> startupModesByJar() throws SQLException {
        Map<String, StartupMode> result = new LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement query = conn.prepareStatement(
                     "SELECT jar_filename, autorun_mode FROM installed_apps ORDER BY name COLLATE NOCASE");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                result.put(rows.getString("jar_filename"), parseStartupMode(rows.getString("autorun_mode")));
            }
        }
        return result;
    }

    public void setStartupMode(String id, StartupMode mode) throws SQLException {
        setStartupModes(Map.of(id, mode));
    }

    /** Save the entire settings panel atomically: no partial updates on failure. */
    public void setStartupModes(Map<String, StartupMode> changes) throws SQLException {
        if (changes == null || changes.isEmpty()) return;
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE installed_apps SET autorun_mode=? WHERE id=?")) {
                for (Map.Entry<String, StartupMode> change : changes.entrySet()) {
                    StartupMode mode = java.util.Objects.requireNonNull(change.getValue(), "startup mode");
                    stmt.setString(1, mode.name());
                    stmt.setString(2, change.getKey());
                    if (stmt.executeUpdate() != 1)
                        throw new SQLException("No existe la aplicación: " + change.getKey());
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /** Uninstall metadata and remembered permissions as a single transaction.
     * App data remains available if the user decides to reinstall later.
     */
    public void removeByFilename(String filename) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement permissions = connection.prepareStatement(
                        "DELETE FROM app_permissions WHERE app_id IN "
                                + "(SELECT id FROM installed_apps WHERE jar_filename = ?)")) {
                    permissions.setString(1, filename);
                    permissions.executeUpdate();
                }
                try (PreparedStatement app = connection.prepareStatement(
                        "DELETE FROM installed_apps WHERE jar_filename = ?")) {
                    app.setString(1, filename);
                    if (app.executeUpdate() != 1)
                        throw new SQLException("El paquete ya no está instalado: " + filename);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
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
