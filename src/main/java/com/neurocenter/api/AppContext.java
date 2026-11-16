package com.neurocenter.api;

import java.awt.Window;
import java.nio.file.Path;

/**
 * Servicios que el panel ofrece a cada app.
 *
 * <p>Los métodos de datos pueden usarse desde el hilo de trabajo del app.
 * {@link #setStatus(String)}, {@link #openApp(String)} y
 * {@link #requestAttention()} despachan internamente las operaciones de interfaz
 * al hilo de Swing. Los componentes Swing y los diálogos deben manipularse
 * únicamente desde ese hilo.</p>
 */
public interface AppContext {

    /** Carpeta privada del app (AppData/CenterOfNeurodivergence/data/&lt;id&gt;). Ya existe. */
    Path dataDir();

    /**
     * Acceso mediado al sistema de archivos. Las rutas relativas se resuelven
     * desde {@link #dataDir()}; las rutas externas pueden requerir permiso.
     */
    default AppStorage storage() {
        throw new UnsupportedOperationException("Esta versión del panel no ofrece acceso mediado al almacenamiento.");
    }

    /** Lee un ajuste guardado del app (se guarda en su carpeta de datos). */
    String getSetting(String key, String defaultValue);

    /** Guarda un ajuste del app. */
    void putSetting(String key, String value);

    /** Muestra un mensaje en la barra de estado inferior. */
    void setStatus(String message);

    /** Cambia a otro app del panel por su id. */
    void openApp(String appId);

    /** Ventana principal, para usarla como padre de diálogos. */
    Window window();

    /** Trae la ventana al frente (y la restaura si estaba minimizada). Útil para avisos. */
    void requestAttention();
}
