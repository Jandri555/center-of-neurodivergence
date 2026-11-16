package com.neurocenter.api;

import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * API anterior para extensiones de Center of Neurodivergence.
 *
 * @deprecated Implementa NeuroApp en las extensiones nuevas. Esta interfaz se mantiene
 *             para conservar compatibilidad binaria con los JAR antiguos.
 */
@Deprecated
public interface NeuroProgram {

    String id();

    String name();

    default String description() { return ""; }

    default String version() { return "1.0"; }

    default Icon icon() { return null; }

    default void start(ProgramContext context) {}

    JComponent createPanel(ProgramContext context);

    default void stop() {}
}
