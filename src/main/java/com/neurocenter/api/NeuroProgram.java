package com.neurocenter.api;

import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * Un app que se muestra dentro del panel de Center of Neurodivergence.
 *
 * Los apps integrados y los apps (.jar en la carpeta "apps") implementan
 * exactamente esta interfaz. Los apps se registran con ServiceLoader: el .jar debe
 * incluir el archivo META-INF/services/com.neurocenter.api.NeuroApp con el nombre
 * completo de la clase (una por línea).
 *
 * La clase necesita un constructor público sin argumentos.
 */
public interface NeuroApp {

    /** Identificador único y estable (p. ej. "agenda"). También da nombre a su carpeta de datos. */
    String id();

    /** Nombre que se muestra en el menú lateral. */
    String name();

    /** Frase corta que se muestra en la pantalla de inicio. */
    default String description() { return ""; }

    default String version() { return "1.0"; }

    /** Icono de 24x24 px. Si devuelve null se genera uno con la inicial del nombre. */
    default Icon icon() { return null; }

    /**
     * Se llama una vez al arrancar la aplicación, aunque el usuario no abra el app.
     * Útil para servicios en segundo plano (avisos, sincronización...). Se ejecuta en el hilo de Swing.
     */
    default void start(AppContext context) {}

    /** Se llama la primera vez que el usuario abre el app. Se ejecuta en el hilo de Swing. */
    JComponent createPanel(AppContext context);

    /** Se llama al cerrar la aplicación. Guarda aquí lo que quede pendiente. */
    default void stop() {}
}
