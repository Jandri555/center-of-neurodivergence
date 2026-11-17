package com.neurocenter.api;

/** Plataforma en la que se está ejecutando una implementación de app. */
public enum NeuroPlatform {
    DESKTOP("escritorio"),
    MOBILE("móvil");

    private final String label;

    NeuroPlatform(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Detecta el runtime actual sin depender de clases Swing ni Android.
     * Android expone android.os.Build; en JVM de escritorio esa clase no existe.
     */
    public static NeuroPlatform current() {
        try {
            Class.forName("android.os.Build", false, NeuroPlatform.class.getClassLoader());
            return MOBILE;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return DESKTOP;
        }
    }
}
