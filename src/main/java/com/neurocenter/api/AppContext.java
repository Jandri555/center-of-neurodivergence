package com.neurocenter.api;

import java.awt.Window;
import java.net.URI;
import com.neurocenter.api.plugin.ServiceRegistry;
import com.neurocenter.api.plugin.EventBus;
import com.neurocenter.api.plugin.ExtensionRegistry;

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

    EventBus events();

    ExtensionRegistry extensions();

    /** Muestra un mensaje en la barra de estado inferior. */
    void setStatus(String message);

    /** Cambia a otro app del panel por su id. */
    void openApp(String appId);

    /** Ventana principal, para usarla como padre de diálogos. */
    Window window();

    /**
     * Abre un enlace HTTP o HTTPS con el navegador predeterminado a través del host.
     * La app no necesita permisos de ejecución del sistema para esta operación.
     */
    default void openExternalUrl(URI uri) {
        throw new UnsupportedOperationException("El host no proporciona apertura de enlaces externos.");
    }

    /**
     * Solicita al host guardar un secreto en el almacén seguro del sistema operativo.
     * Las claves se aíslan automáticamente por identificador de aplicación.
     * Las aplicaciones externas necesitan el permiso «credentials.access» concedido
     * por el usuario en el gestor de permisos de NeuroCenter.
     */
    default void saveSecret(String key, char[] secret) {
        throw new UnsupportedOperationException("El host no proporciona un almacén seguro de credenciales.");
    }

    /** Recupera un secreto guardado, o null cuando no existe. Requiere credentials.access en apps externas. */
    default char[] loadSecret(String key) {
        throw new UnsupportedOperationException("El host no proporciona un almacén seguro de credenciales.");
    }

    /** Elimina un secreto guardado. Requiere credentials.access en apps externas. */
    default void deleteSecret(String key) {
        throw new UnsupportedOperationException("El host no proporciona un almacén seguro de credenciales.");
    }

    /** Trae la ventana al frente (y la restaura si estaba minimizada). Útil para avisos. */
    void requestAttention();
}
