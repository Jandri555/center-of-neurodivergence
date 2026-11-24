package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** The pre-release schema is intentionally not upgraded behind the user's back. */
class AppRegistryNoMigrationTest {
    @TempDir Path temp;

    @Test
    void legacyAppSchemaIsNotRewrittenOrUpgraded() throws Exception {
        Path db = temp.resolve("oldapps.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE installed_apps (id TEXT PRIMARY KEY, " +
                    "jar_filename TEXT, autorun INTEGER NOT NULL DEFAULT 0)");
        }
        AppRegistry registry = new AppRegistry(db);
        assertThrows(java.sql.SQLException.class, registry::listApps);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             java.sql.ResultSet columns = s.executeQuery("PRAGMA table_info(installed_apps)")) {
            java.util.Set<String> actual = new java.util.HashSet<>();
            while (columns.next()) actual.add(columns.getString("name"));
            assertEquals(java.util.Set.of("id", "jar_filename", "autorun"), actual);
        }
    }
}
