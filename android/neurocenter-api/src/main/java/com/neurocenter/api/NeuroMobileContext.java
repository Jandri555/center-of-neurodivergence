package com.neurocenter.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;

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

    /** Comprueba una capacidad concedida por el usuario en el gestor de permisos del host. */
    default boolean hasPermission(String permissionId) {
        return false;
    }

    /** Falla de forma cerrada si la app no tiene concedida la capacidad indicada. */
    default void requirePermission(String permissionId) {
        if (!NeuroMobilePermissions.isSupported(permissionId) || !hasPermission(permissionId)) {
            throw new SecurityException("La app no tiene concedido el permiso: " + permissionId);
        }
    }

    /** Abre una conexión de red; requiere network.connect. */
    default URLConnection openConnection(URL url) throws IOException {
        requirePermission(NeuroMobilePermissions.NETWORK_CONNECT);
        return url.openConnection();
    }

    /** Abre un socket servidor; requiere network.listen. */
    default ServerSocket openServerSocket(int port) throws IOException {
        requirePermission(NeuroMobilePermissions.NETWORK_LISTEN);
        return new ServerSocket(port);
    }

    /** Solicita al host el selector del sistema para leer un documento. */
    void requestDocumentRead(String mimeType, int requestCode);

    /** Solicita al host el selector del sistema para crear un documento. */
    void requestDocumentWrite(String mimeType, String suggestedName, int requestCode);

    /** Abre un URI obtenido mediante el selector del sistema; requiere files.read.documents. */
    InputStream openDocumentInputStream(Uri uri) throws IOException;

    /** Abre un URI obtenido mediante el selector del sistema; requiere files.write.documents. */
    OutputStream openDocumentOutputStream(Uri uri) throws IOException;

    /** Lee texto del portapapeles; requiere clipboard.access. */
    CharSequence readClipboardText();

    /** Escribe texto en el portapapeles; requiere clipboard.access. */
    void writeClipboardText(CharSequence text);

    /** Guarda un secreto cifrado con Android Keystore; requiere credentials.access. */
    void saveSecret(String key, char[] secret) throws IOException;

    /** Recupera un secreto o null si no existe; requiere credentials.access. */
    char[] loadSecret(String key) throws IOException;

    /** Elimina un secreto; requiere credentials.access. */
    void deleteSecret(String key) throws IOException;

    /** Lanza una actividad y devuelve el resultado a esta app, aunque cambie la app visible. */
    void startActivityForResult(Intent intent, int requestCode);

    /** Solicita permisos Android y devuelve el resultado a esta app. */
    void requestPermissions(String[] permissions, int requestCode);
}
