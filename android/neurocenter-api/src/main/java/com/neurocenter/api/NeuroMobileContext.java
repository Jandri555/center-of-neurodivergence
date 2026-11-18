package com.neurocenter.api;

import android.app.Activity;
import android.content.Context;

/**
 * Servicios Android que recibe una implementación móvil de una app.
 * La lógica que no dependa de interfaz puede compartir código mediante NeuroAppContext.
 */
public interface NeuroMobileContext extends NeuroAppContext {

    /** El host Android entrega este contexto únicamente a implementaciones móviles. */
    @Override
    default NeuroPlatform platform() {
        return NeuroPlatform.MOBILE;
    }

    /** Activity que aloja la app móvil actual. */
    Activity activity();

    /** Contexto Android de la aplicación, independiente del ciclo de vida de la Activity. */
    Context applicationContext();

    /** Muestra un mensaje breve en la barra de estado del host (cadena vacía para borrarlo). */
    void setStatus(String message);

    /** Navega a otra app mediante su identificador estable. */
    void openApp(String appId);

    /**
     * Color ARGB del tema activo. Las claves son las de {@code colors} en theme.json
     * ("background", "surface", "text", "primary", "border", "button", "buttonText",
     * "input", "inputText", "selection", "selectionText", "dialog"…) más "muted"
     * (texto secundario). Devuelve {@code fallback} si la clave no existe.
     */
    default int themeColor(String key, int fallback) {
        return fallback;
    }

    /** true si el tema activo es oscuro. */
    default boolean isDarkTheme() {
        return false;
    }
}
