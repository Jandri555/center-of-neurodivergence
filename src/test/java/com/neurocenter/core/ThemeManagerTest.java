package com.neurocenter.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeManagerTest {

    @Test
    void builtInDarkThemeUsesDeepCharcoalBackground() {
        ThemeManager.ThemeInfo dark = ThemeManager.availableThemes().stream()
                .filter(theme -> "dark".equals(theme.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("#181818", dark.colors().get("background").getAsString());
        assertEquals("#202020", dark.colors().get("surface").getAsString());
        assertEquals("#292929", dark.colors().get("button").getAsString());
    }
}
