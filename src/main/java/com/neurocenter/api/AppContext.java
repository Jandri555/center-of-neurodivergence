package com.neurocenter.api;

import java.awt.Window;
import com.neurocenter.api.plugin.ServiceRegistry;

/**
 * Servicios que el panel ofrece a cada app.
 *
 * <p>Los métodos de datos pueden usarse desde el hilo de trabajo del app.
 * {@link #setStatus(String)}, {@link #openApp(String)} y
 * {@link #requestAttention()} despachan internamente las operaciones de interfaz
 * al hilo de Swing. Los componentes Swing y los diálogos deben manipularse
 * únicamente desde ese hilo.</p>
 */
public interface AppContext extends NeuroAppContext {

    /** En el host Swing, esta implementación siempre se ejecuta en escritorio. */
    @Override
    default NeuroPlatform platform() {
        return NeuroPlatform.DESKTOP;
    }

    /** Servicios publicados por plugins. Las invocaciones se ejecutan en contexto privilegiado. */
    ServiceRegistry services();

    /** Muestra un mensaje en la barra de estado inferior. */
    void setStatus(String message);

    /** Cambia a otro app del panel por su id. */
    void openApp(String appId);

    /** Ventana principal, para usarla como padre de diálogos. */
    Window window();

    /** Trae la ventana al frente (y la restaura si estaba minimizada). Útil para avisos. */
    void requestAttention();
}
