package com.neurocenter.core;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro SQLite de las aplicaciones externas instaladas (apps.db).
 * Misma tabla que en escritorio; la base de datos es la fuente de verdad y la carpeta
 * apps/ solo almacena los paquetes (.apk / .jar).
 */
public final class AppRegistry implements AutoCloseable {

    public static final class AppRecord {
        public final String id, name, author, version, fileName, installedAt;
        public final long sizeBytes;
        public final boolean autorun;

        AppRecord(String id, String name, String author, String version, String fileName,
                  String installedAt, long sizeBytes, boolean autorun) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.version = version;
            this.fileName = fileName;
            this.installedAt = installedAt;
            this.sizeBytes = sizeBytes;
            this.autorun = autorun;
        }

        @Override
        public String toString() {
            return name + (autorun ? " — inicio automático: sí" : " — inicio automático: no");
        }
    }

    private final SQLiteDatabase db;

    public AppRegistry(Path databaseFile) throws IOException {
        Path absolute = databaseFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        db = SQLiteDatabase.openOrCreateDatabase(absolute.toString(), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS installed_apps ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT NOT NULL, "
                + "author TEXT NOT NULL, "
                + "version TEXT NOT NULL, "
                + "file_name TEXT NOT NULL UNIQUE, "
                + "installed_at TEXT NOT NULL, "
                + "size_bytes INTEGER NOT NULL, "
                + "autorun INTEGER NOT NULL DEFAULT 0 CHECK (autorun IN (0, 1)))");
    }

    /** Registra un paquete ya validado y copiado a apps/. */
    public synchronized AppRecord register(String id, String name, String author, String version,
                                           String fileName, long sizeBytes) throws IOException {
        String installedAt = Instant.now().toString();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("name", name);
        values.put("author", author);
        values.put("version", version);
        values.put("file_name", fileName);
        values.put("installed_at", installedAt);
        values.put("size_bytes", sizeBytes);
        values.put("autorun", 0);
        try {
            db.insertOrThrow("installed_apps", null, values);
        } catch (SQLiteConstraintException ex) {
            throw new IOException("Ya hay una app instalada con el identificador «" + id
                    + "» o con el archivo «" + fileName + "».", ex);
        }
        return new AppRecord(id, name, author, version, fileName, installedAt, sizeBytes, false);
    }

    public synchronized List<AppRecord> listApps() {
        List<AppRecord> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, name, author, version, file_name, installed_at, "
                + "size_bytes, autorun FROM installed_apps ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                result.add(new AppRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getLong(6), c.getInt(7) == 1));
            }
        }
        return result;
    }

    public synchronized boolean containsId(String id) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM installed_apps WHERE id = ?", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    public synchronized List<String> installedFileNames() {
        return queryStrings("SELECT file_name FROM installed_apps ORDER BY file_name");
    }

    public synchronized List<String> autorunFileNames() {
        return queryStrings("SELECT file_name FROM installed_apps WHERE autorun = 1 "
                + "ORDER BY name COLLATE NOCASE");
    }

    public synchronized void setAutorun(String id, boolean autorun) {
        ContentValues values = new ContentValues();
        values.put("autorun", autorun ? 1 : 0);
        db.update("installed_apps", values, "id = ?", new String[]{id});
    }

    public synchronized void removeByFileName(String fileName) {
        db.delete("installed_apps", "file_name = ?", new String[]{fileName});
    }

    private List<String> queryStrings(String sql) {
        List<String> result = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (db.isOpen()) db.close();
    }
}
