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
 *
 * <p>Cada app abierta tiene un hilo de trabajo propio para su ciclo de vida:
 * {@link #start(AppContext)} y {@link #stop()} se ejecutan en ese hilo.
 * {@link #createPanel(AppContext)} se ejecuta en el hilo de Swing (EDT), porque
 * Swing exige que la interfaz gráfica se cree y actualice desde ese hilo.</p>
 *
 * <p>Indicador opcional de cambios pendientes: la clase que implementa esta
 * interfaz puede declarar
 * {@code public static volatile boolean UNSAVED_CHANGES = false;}.
 * El panel principal lo consulta al pulsar la X. Si vale {@code true}, pide
 * confirmación antes de cerrar. Si el campo no existe o vale {@code false},
 * cierra directamente. También se admite un campo boolean no estático.</p>
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
     * Se llama cada vez que el usuario abre el app, en su hilo de trabajo propio.
     * Debe reservarse para inicialización y tareas que no manipulen componentes Swing.
     */
    default void start(AppContext context) {}

    /** Crea la interfaz del app. Se ejecuta en el hilo de Swing (EDT). */
    JComponent createPanel(AppContext context);

    /** Se llama al cerrar el app, en el mismo hilo de trabajo que ejecutó start(). */
    default void stop() {}
}
