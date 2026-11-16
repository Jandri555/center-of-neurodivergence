package com.neurocenter.core;

/**
 * Información común de la aplicación.
 * La versión se obtiene del manifiesto generado por Maven.
 */
public final class AppInfo {
    /** Nombre visible. Cámbialo aquí si quieres rebautizar la aplicación. */
    public static final String NAME = "Center of Neurodivergence";

    public static final String VERSION = resolveVersion();

    private AppInfo() {}

    private static String resolveVersion() {
        Package appPackage = AppInfo.class.getPackage();
        String version = appPackage != null
                ? appPackage.getImplementationVersion()
                : null;

        // Al ejecutar desde el IDE no suele existir un manifiesto de JAR.
        return version != null ? version : "DEV";
    }
}
