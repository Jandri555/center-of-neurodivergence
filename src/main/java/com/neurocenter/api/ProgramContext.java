package com.neurocenter.api;

import java.awt.Window;
import java.nio.file.Path;

/** Lo que el panel ofrece a cada programa. Todos los métodos deben llamarse desde el hilo de Swing. */
public interface ProgramContext {

    /** Carpeta privada del programa (AppData/CenterOfNeurodivergence/data/&lt;id&gt;). Ya existe. */
    Path dataDir();

    /** Lee un ajuste guardado del programa (se guarda en su carpeta de datos). */
    String getSetting(String key, String defaultValue);

    /** Guarda un ajuste del programa. */
    void putSetting(String key, String value);

    /** Muestra un mensaje en la barra de estado inferior. */
    void setStatus(String message);

    /** Cambia a otro programa del panel por su id. */
    void openProgram(String programId);

    /** Ventana principal, para usarla como padre de diálogos. */
    Window window();

    /** Trae la ventana al frente (y la restaura si estaba minimizada). Útil para avisos. */
    void requestAttention();
}
