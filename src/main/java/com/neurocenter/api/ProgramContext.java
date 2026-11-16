package com.neurocenter.api;

import java.awt.Window;
import java.nio.file.Path;

/**
 * API de contexto anterior a NeuroApp.
 *
 * @deprecated Se conserva para que los JAR compilados con NeuroProgram sigan funcionando.
 *             Las apps nuevas deben utilizar AppContext.
 */
@Deprecated
public interface ProgramContext {

    Path dataDir();

    String getSetting(String key, String defaultValue);

    void putSetting(String key, String value);

    void setStatus(String message);

    void openProgram(String programId);

    Window window();

    void requestAttention();
}
