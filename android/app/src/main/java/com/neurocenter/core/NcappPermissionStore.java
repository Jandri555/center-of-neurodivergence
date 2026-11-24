package com.neurocenter.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.neurocenter.api.NeuroCenterCapabilities;

import java.util.HashSet;
import java.util.Set;

/** Concesiones vinculadas al ID y SHA-256 EXACTO del archivo .ncapp. */
public final class NcappPermissionStore {
    private NcappPermissionStore() {}

    private static String key(NcappManager.InstalledApp app) {
        return app.id() + ":" + app.sha256();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ncapp_grants", Context.MODE_PRIVATE);
    }

    public static Set<String> granted(Context context, NcappManager.InstalledApp app) {
        Set<String> saved = prefs(context).getStringSet(key(app), Set.of());
        Set<String> valid = new HashSet<>();
        for (String permission : saved) {
            if (NeuroCenterCapabilities.isSupported(permission)
                    && app.declaredPermissions().contains(permission)) valid.add(permission);
        }
        return Set.copyOf(valid);
    }

    public static boolean has(Context context, NcappManager.InstalledApp app, String permission) {
        return app.declaredPermissions().contains(permission)
                && NeuroCenterCapabilities.isSupported(permission)
                && granted(context, app).contains(permission);
    }

    public static void setGranted(Context context, NcappManager.InstalledApp app,
                                  Set<String> permissions) {
        Set<String> safe = new HashSet<>();
        for (String permission : permissions) {
            if (!NeuroCenterCapabilities.isSupported(permission)
                    || !app.declaredPermissions().contains(permission)) {
                throw new IllegalArgumentException("Permiso desconocido: " + permission);
            }
            safe.add(permission);
        }
        if (!prefs(context).edit().putStringSet(key(app), safe).commit()) {
            throw new IllegalStateException("No se pudieron guardar los permisos.");
        }
    }

    /** null: ask, true: always allow, false: always deny. */
    public static Boolean decision(Context context, NcappManager.InstalledApp app,
                                   String permission) {
        if (!app.declaredPermissions().contains(permission)) return false;
        if (has(context, app, permission)) return true;
        Set<String> denied = prefs(context).getStringSet(key(app) + ":denied", Set.of());
        if (denied.contains(permission)) return false;
        return null;
    }

    public static void setDecision(Context context, NcappManager.InstalledApp app,
                                   String permission, Boolean allow) {
        if (!NeuroCenterCapabilities.isSupported(permission)
                || !app.declaredPermissions().contains(permission)) {
            throw new SecurityException("Permiso no declarado por la .ncapp: " + permission);
        }
        Set<String> granted = new HashSet<>(granted(context, app));
        Set<String> denied = new HashSet<>(prefs(context).getStringSet(key(app) + ":denied", Set.of()));
        granted.remove(permission);
        denied.remove(permission);
        if (Boolean.TRUE.equals(allow)) granted.add(permission);
        if (Boolean.FALSE.equals(allow)) denied.add(permission);
        if (!prefs(context).edit()
                .putStringSet(key(app), granted)
                .putStringSet(key(app) + ":denied", denied).commit()) {
            throw new IllegalStateException("No se pudo guardar la decisión del permiso.");
        }
    }

    public static void revoke(Context context, NcappManager.InstalledApp app) {
        if (!prefs(context).edit().remove(key(app)).remove(key(app) + ":denied").commit()) {
            throw new IllegalStateException("No se pudieron revocar los permisos.");
        }
    }
}
