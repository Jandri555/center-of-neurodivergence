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
import java.util.Collections;
import java.util.Set;

/**
 * Persists user grants for NeuroCenter-owned services, keyed by Android package name.
 *
 * This database does not grant Android OS permissions. Those are owned by the installed
 * package and managed by Android's Package Manager.
 */
public final class NeuroCenterCapabilityStore implements Closeable {

    private final SQLiteDatabase db;
    private final Context context;
    // One-use grants are bound to the authenticated APK signature; they never
    // survive process death and expire if the client does not use them promptly.
    private static final java.util.concurrent.ConcurrentMap<String, Long> oneTimeGrants =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ONE_TIME_TTL_MS = 120_000;

    private static String onceKey(String pkg, String signer, String capability) {
        return pkg + ":" + signer + ":" + capability;
    }


    public NeuroCenterCapabilityStore(Context context) throws IOException {
        this.context = context.getApplicationContext();
        Path file = AppPaths.root(context).resolve("capabilities.db").toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        db = SQLiteDatabase.openOrCreateDatabase(file.toString(), null);
        // Older pre-release builds stored grants without a signer identity. They
        // are deliberately discarded instead of migrating unbound authorizations.
        if (tableExists(db, "app_capabilities") && !hasColumn(db, "app_capabilities", "signing_digest")) {
            db.execSQL("DROP TABLE app_capabilities");
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS app_capabilities ("
                + "package_name TEXT NOT NULL, "
                + "signing_digest TEXT NOT NULL, "
                + "capability_id TEXT NOT NULL, "
                + "granted INTEGER NOT NULL DEFAULT 1 CHECK (granted IN (0, 1)), "
                + "PRIMARY KEY (package_name, signing_digest, capability_id))");
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (name >= 0 && column.equals(c.getString(name))) return true;
            }
        }
        return false;
    }

    public synchronized Set<String> grantedCapabilities(String packageName, String signingDigest) {
        validateIdentity(packageName, signingDigest);
        Set<String> result = new LinkedHashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT capability_id FROM app_capabilities "
                        + "WHERE package_name = ? AND signing_digest = ? AND granted = 1 ORDER BY capability_id",
                new String[]{packageName, signingDigest})) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (NeuroCenterCapabilities.isSupported(id)
                        && AndroidExternalApps.declaredCapabilities(context, packageName).contains(id)) result.add(id);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public synchronized boolean isGranted(String packageName, String signingDigest, String capabilityId) {
        validateIdentity(packageName, signingDigest);
        if (!NeuroCenterCapabilities.isSupported(capabilityId)
                || !AndroidExternalApps.declaredCapabilities(context, packageName).contains(capabilityId))
            return false;
        if (hasOneTimeGrant(packageName, signingDigest, capabilityId)) return true;
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM app_capabilities "
                        + "WHERE package_name = ? AND signing_digest = ? AND capability_id = ? AND granted = 1",
                new String[]{packageName, signingDigest, capabilityId})) {
            return c.moveToFirst();
        }
    }

    /** Replaces one package's grant set atomically. Empty set revokes every capability. */
    public synchronized void setGrantedCapabilities(String packageName, String signingDigest,
                                                     Set<String> capabilityIds) {
        validateIdentity(packageName, signingDigest);
        Set<String> safe = new LinkedHashSet<>();
        if (capabilityIds != null) {
            for (String id : capabilityIds) {
                if (!NeuroCenterCapabilities.isSupported(id)
                        || !AndroidExternalApps.declaredCapabilities(context, packageName).contains(id)) {
                    throw new IllegalArgumentException("Capacidad no reconocida: " + id);
                }
                safe.add(id);
            }
        }

        db.beginTransaction();
        try {
            db.delete("app_capabilities", "package_name = ? AND signing_digest = ?",
                    new String[]{packageName, signingDigest});
            for (String id : safe) {
                ContentValues values = new ContentValues();
                values.put("package_name", packageName);
                values.put("signing_digest", signingDigest);
                values.put("capability_id", id);
                values.put("granted", 1);
                db.insertOrThrow("app_capabilities", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized Boolean storedDecision(String packageName, String signer, String capability) {
        validateIdentity(packageName, signer);
        if (!AndroidExternalApps.declaredCapabilities(context, packageName).contains(capability))
            return false;
        try (Cursor c = db.rawQuery(
                "SELECT granted FROM app_capabilities WHERE package_name=? AND signing_digest=? AND capability_id=?",
                new String[]{packageName, signer, capability})) {
            return c.moveToFirst() ? c.getInt(0) == 1 : null;
        }
    }

    public synchronized void setDecision(String packageName, String signer,
                                         String capability, Boolean allow) {
        validateIdentity(packageName, signer);
        if (!AndroidExternalApps.declaredCapabilities(context, packageName).contains(capability))
            throw new SecurityException("Capacidad no declarada: " + capability);
        oneTimeGrants.remove(onceKey(packageName, signer, capability));
        if (allow == null) {
            db.delete("app_capabilities",
                    "package_name=? AND signing_digest=? AND capability_id=?",
                    new String[]{packageName,signer,capability});
        } else {
            ContentValues values = new ContentValues();
            values.put("package_name", packageName);
            values.put("signing_digest", signer);
            values.put("capability_id", capability);
            values.put("granted", allow ? 1 : 0);
            db.insertWithOnConflict("app_capabilities", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public static void grantOnce(String pkg, String signer, String capability) {
        oneTimeGrants.put(onceKey(pkg, signer, capability),
                android.os.SystemClock.elapsedRealtime() + ONE_TIME_TTL_MS);
    }

    private static boolean hasOneTimeGrant(String pkg, String signer, String capability) {
        String key = onceKey(pkg, signer, capability);
        Long deadline = oneTimeGrants.get(key);
        if (deadline == null) return false;
        if (deadline < android.os.SystemClock.elapsedRealtime()) {
            oneTimeGrants.remove(key, deadline);
            return false;
        }
        return true;
    }

    /** Use this for privileged Binder operations so allow-once cannot be reused. */
    public synchronized boolean consumeAuthorization(String pkg, String signer, String capability) {
        if (!isGranted(pkg, signer, capability)) return false;
        if (Boolean.TRUE.equals(storedDecision(pkg, signer, capability))) return true;
        return oneTimeGrants.remove(onceKey(pkg, signer, capability)) != null;
    }

    /** Removes obsolete grants when a package is uninstalled. */
    public synchronized void removePackage(String packageName) {
        validatePackageName(packageName);
        db.delete("app_capabilities", "package_name = ?", new String[]{packageName});
    }

    private static void validateIdentity(String packageName, String signingDigest) {
        validatePackageName(packageName);
        if (signingDigest == null || signingDigest.length() > 512
                || !signingDigest.matches("[a-f0-9]{64}(,[a-f0-9]{64})*")) {
            throw new IllegalArgumentException("Huella de firma Android no válida.");
        }
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
