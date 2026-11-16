package com.neurocenter.core;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.util.List;

/**
 * Registro central de temas disponibles.
 *
 * Los temas integrados se declaran aquí. El catálogo está separado de la
 * interfaz para poder añadir temas externos (.tme) en una futura versión.
 */
public final class ThemeManager {

    public record ThemeInfo(String id, String name) {}

    private static final List<ThemeInfo> BUILT_IN_THEMES = List.of(
            new ThemeInfo("light", "Claro"),
            new ThemeInfo("dark", "Oscuro")
    );

    private ThemeManager() {}

    public static List<ThemeInfo> availableThemes() {
        return BUILT_IN_THEMES;
    }

    public static void apply(String themeId) {
        if ("dark".equals(themeId)) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
    }

    /** Compatibilidad con el arranque actual. */
    public static void apply(boolean dark) {
        apply(dark ? "dark" : "light");
    }
}
