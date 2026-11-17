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
}
