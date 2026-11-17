package com.neurocenter.api;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Implementación nativa Android de una app multiplataforma.
 *
 * <p>La implementación de escritorio implementa NeuroApp y usa Swing; la móvil
 * implementa esta interfaz y devuelve una View Android. Ambas deben compartir
 * el mismo id de app y pueden delegar su lógica común a clases Java neutrales.</p>
 */
public interface NeuroMobileApp {

    String id();

    String name();

    default String description() {
        return "";
    }

    default String version() {
        return "1.0";
    }

    /** Se llama cuando el host inicia esta app. */
    default void onStart(NeuroMobileContext context) {}

    /** Construye la interfaz nativa Android de la app. */
    View createView(NeuroMobileContext context, LayoutInflater inflater, ViewGroup parent);

    /** Se llama cuando el host cierra esta app. */
    default void onStop() {}
}
