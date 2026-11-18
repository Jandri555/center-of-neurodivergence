package com.neurocenter.api;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Implementación nativa Android de una app multiplataforma.
 *
 * <p>La implementación de escritorio implementa NeuroApp y usa Swing; la móvil
 * implementa esta interfaz y devuelve una View Android. Ambas deben compartir
 * el mismo id de app y pueden delegar su lógica común a clases Java neutrales.</p>
 *
 * <h2>Hilos y ciclo de vida</h2>
 * <p>Igual que en escritorio, cada app abierta tiene un hilo de trabajo propio:</p>
 * <ul>
 *   <li>{@link #onStart(NeuroMobileContext)} y {@link #onStop()} se ejecutan en ese hilo
 *       (no en el hilo principal), así que pueden hacer E/S sin bloquear la interfaz.</li>
 *   <li>{@link #createView}, {@link #onResume()}, {@link #onPause()},
 *       {@link #onActivityResult} y {@link #onRequestPermissionsResult} se ejecutan en el
 *       hilo principal de Android.</li>
 *   <li>{@link #createView} puede llamarse más de una vez sobre la misma instancia
 *       (por ejemplo, al cambiar de tema): la app debe descartar las referencias a la
 *       vista anterior y construir una nueva con el estado que ya tiene.</li>
 *   <li>{@link #onResume()} se llama cuando la app es la visible <em>y</em> la pantalla está
 *       en primer plano; {@link #onPause()} cuando deja de serlo. Siempre van emparejados.</li>
 * </ul>
 *
 * <p>La clase necesita un constructor público sin argumentos.</p>
 *
 * <h2>Apps externas (APK o JAR con classes.dex)</h2>
 * <p>El host las carga con DexClassLoader. Se descubren de dos maneras (se admiten ambas):</p>
 * <ul>
 *   <li>Lista de clases separadas por comas en el metadato {@code NeuroCenter-App-Class}
 *       (meta-data del AndroidManifest en un APK; entrada del manifiesto en un JAR).</li>
 *   <li>El archivo {@code META-INF/services/com.neurocenter.api.NeuroMobileApp}.</li>
 * </ul>
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

    /** Icono cuadrado. Si devuelve null el host genera uno con la inicial del nombre. */
    default Drawable icon() {
        return null;
    }

    /**
     * Se llama una vez cada vez que el usuario abre la app, en su hilo de trabajo propio.
     * Debe reservarse para inicialización y tareas que no manipulen vistas.
     */
    default void onStart(NeuroMobileContext context) {}

    /**
     * Construye la interfaz nativa Android de la app (hilo principal). Devuelve una vista
     * sin padre: el host la añade a su pantalla. Puede llamarse varias veces.
     */
    View createView(NeuroMobileContext context, LayoutInflater inflater, ViewGroup parent);

    /** La app está visible y en primer plano (hilo principal). */
    default void onResume() {}

    /** La app deja de estar visible o la pantalla pasa a segundo plano (hilo principal). */
    default void onPause() {}

    /**
     * Resultado de una actividad que lanzó esta app con
     * {@code context.activity().startActivityForResult(...)}. Elige códigos de petición
     * propios y distintos de los del host (el host usa 9100–9199).
     */
    default void onActivityResult(int requestCode, int resultCode, Intent data) {}

    /** Resultado de una petición de permisos lanzada por esta app (hilo principal). */
    default void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {}

    /** Se llama al cerrar la app, en el mismo hilo de trabajo que ejecutó onStart(). */
    default void onStop() {}
}
