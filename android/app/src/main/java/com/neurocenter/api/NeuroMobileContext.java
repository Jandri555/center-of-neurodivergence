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

    /** Muestra un mensaje breve en la interfaz móvil del host. */
    void setStatus(String message);

    /** Navega a otra app mediante su identificador estable. */
    void openApp(String appId);
}
