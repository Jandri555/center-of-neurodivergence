package com.neurocenter.core;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

public final class ThemeManager {

    private ThemeManager() {}

    public static void apply(boolean dark) {
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        FlatLaf.updateUI();
    }
}
