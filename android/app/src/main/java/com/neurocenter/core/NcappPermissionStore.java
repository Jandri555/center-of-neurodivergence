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
            if (NeuroCenterCapabilities.isSupported(permission)) valid.add(permission);
        }
        return Set.copyOf(valid);
    }

    public static boolean has(Context context, NcappManager.InstalledApp app, String permission) {
        return NeuroCenterCapabilities.isSupported(permission)
                && granted(context, app).contains(permission);
    }

    public static void setGranted(Context context, NcappManager.InstalledApp app,
                                  Set<String> permissions) {
        Set<String> safe = new HashSet<>();
        for (String permission : permissions) {
            if (!NeuroCenterCapabilities.isSupported(permission)) {
                throw new IllegalArgumentException("Permiso desconocido: " + permission);
            }
            safe.add(permission);
        }
        if (!prefs(context).edit().putStringSet(key(app), safe).commit()) {
            throw new IllegalStateException("No se pudieron guardar los permisos.");
        }
    }

    public static void revoke(Context context, NcappManager.InstalledApp app) {
        if (!prefs(context).edit().remove(key(app)).commit()) {
            throw new IllegalStateException("No se pudieron revocar los permisos.");
        }
    }
}
