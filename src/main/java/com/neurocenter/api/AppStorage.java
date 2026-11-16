package com.neurocenter.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Acceso a archivos para las apps del panel.
 *
 * <p>Las rutas relativas se resuelven desde {@link AppContext#dataDir()}.
 * El acceso a rutas fuera de esa carpeta pasa por el sistema de permisos
 * del panel. Las apps deben utilizar esta API en lugar de acceder directamente
 * al sistema de archivos cuando quieran respetar los permisos concedidos.</p>
 *
 * <p>Esta interfaz es una API cooperativa, no un sandbox: el código Java externo
 * ejecutado en el mismo proceso puede intentar utilizar directamente otras APIs
 * de archivos.</p>
 */
public interface AppStorage {

    /** Comprueba si existe una ruta. La comprobación puede solicitar permiso. */
    boolean exists(Path path) throws IOException;

    /** Lista el contenido inmediato de una carpeta. */
    List<Path> list(Path directory) throws IOException;

    /** Abre un archivo para lectura. */
    InputStream openRead(Path file) throws IOException;

    /** Abre un archivo para escritura, creándolo o sobrescribiéndolo. */
    OutputStream openWrite(Path file) throws IOException;

    /** Crea una carpeta y todos sus directorios padre. */
    void createDirectories(Path directory) throws IOException;

    /** Elimina un archivo o una carpeta vacía. */
    void delete(Path path) throws IOException;

    /** Lee el contenido completo de un archivo. */
    default byte[] readAllBytes(Path file) throws IOException {
        try (InputStream input = openRead(file)) {
            return input.readAllBytes();
        }
    }

    /** Escribe el contenido completo de un archivo. */
    default void write(Path file, byte[] contents) throws IOException {
        try (OutputStream output = openWrite(file)) {
            output.write(contents);
        }
    }
}
