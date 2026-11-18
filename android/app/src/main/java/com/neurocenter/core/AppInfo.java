package com.neurocenter.core;

import android.content.Context;
import android.content.pm.PackageManager;

/** Información común de la aplicación (equivalente móvil de AppInfo de escritorio). */
public final class AppInfo {

    /** Nombre visible. */
    public static final String NAME = "Center of Neurodivergence";

    private AppInfo() {}

    /** Versión de la app según el APK instalado (versionName de Gradle). */
    @SuppressWarnings("deprecation")
    public static String version(Context context) {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.trim().isEmpty() ? "DEV" : version;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return "DEV";
        }
    }
}
