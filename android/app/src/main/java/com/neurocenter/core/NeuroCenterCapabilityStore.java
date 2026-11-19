package com.neurocenter.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.neurocenter.api.NeuroCenterCapabilities;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists user grants for NeuroCenter-owned services, keyed by Android package name.
 *
 * This database does not grant Android OS permissions. Those are owned by the installed
 * package and managed by Android's Package Manager.
 */
public final class NeuroCenterCapabilityStore implements Closeable {

    private final SQLiteDatabase db;

    public NeuroCenterCapabilityStore(Context context) throws IOException {
        Path file = AppPaths.root(context).resolve("capabilities.db").toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        db = SQLiteDatabase.openOrCreateDatabase(file.toString(), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS app_capabilities ("
                + "package_name TEXT NOT NULL, "
                + "capability_id TEXT NOT NULL, "
                + "granted INTEGER NOT NULL DEFAULT 1 CHECK (granted IN (0, 1)), "
                + "PRIMARY KEY (package_name, capability_id))");
    }

    public synchronized Set<String> grantedCapabilities(String packageName) {
        validatePackageName(packageName);
        Set<String> result = new LinkedHashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT capability_id FROM app_capabilities "
                        + "WHERE package_name = ? AND granted = 1 ORDER BY capability_id",
                new String[]{packageName})) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (NeuroCenterCapabilities.isSupported(id)) result.add(id);
            }
        }
        return Set.copyOf(result);
    }

    public synchronized boolean isGranted(String packageName, String capabilityId) {
        validatePackageName(packageName);
        if (!NeuroCenterCapabilities.isSupported(capabilityId)) return false;
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM app_capabilities "
                        + "WHERE package_name = ? AND capability_id = ? AND granted = 1",
                new String[]{packageName, capabilityId})) {
            return c.moveToFirst();
        }
    }

    /** Replaces one package's grant set atomically. Empty set revokes every capability. */
    public synchronized void setGrantedCapabilities(String packageName, Set<String> capabilityIds) {
        validatePackageName(packageName);
        Set<String> safe = new LinkedHashSet<>();
        if (capabilityIds != null) {
            for (String id : capabilityIds) {
                if (!NeuroCenterCapabilities.isSupported(id)) {
                    throw new IllegalArgumentException("Capacidad no reconocida: " + id);
                }
                safe.add(id);
            }
        }

        db.beginTransaction();
        try {
            db.delete("app_capabilities", "package_name = ?", new String[]{packageName});
            for (String id : safe) {
                ContentValues values = new ContentValues();
                values.put("package_name", packageName);
                values.put("capability_id", id);
                values.put("granted", 1);
                db.insertOrThrow("app_capabilities", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Removes obsolete grants when a package is uninstalled. */
    public synchronized void removePackage(String packageName) {
        validatePackageName(packageName);
        db.delete("app_capabilities", "package_name = ?", new String[]{packageName});
    }

    private static void validatePackageName(String packageName) {
        if (packageName == null
                || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || packageName.length() > 255) {
            throw new IllegalArgumentException("Nombre de paquete Android no válido.");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (db.isOpen()) db.close();
    }
}
