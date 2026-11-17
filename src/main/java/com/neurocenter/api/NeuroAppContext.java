package com.neurocenter.api;

import java.nio.file.Path;

/**
 * Servicios independientes de la interfaz que una app puede usar en cualquier plataforma.
 *
 * <p>La lógica compartida de una app puede depender de este contrato sin importar
 * Swing ni Android. Cada host proporciona su implementación del contexto.</p>
 */
public interface NeuroAppContext {

    /** Plataforma real proporcionada por el host que está ejecutando la app. */
    NeuroPlatform platform();

    /** Carpeta privada de datos de la app. */
    Path dataDir();

    /** Lee un ajuste guardado de la app. */
    String getSetting(String key, String defaultValue);

    /** Guarda un ajuste de la app. */
    void putSetting(String key, String value);
}
