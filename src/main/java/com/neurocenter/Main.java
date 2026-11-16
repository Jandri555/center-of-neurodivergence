package com.neurocenter;

import com.neurocenter.core.AppPaths;
import com.neurocenter.core.MainWindow;
import com.neurocenter.core.PropertiesStore;
import com.neurocenter.core.ThemeManager;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {

    public static void main(String[] args) {
        AppPaths.ensureDirectories();
        PropertiesStore settings = new PropertiesStore(AppPaths.root().resolve("settings.properties"));
        boolean dark = "dark".equals(settings.get("theme", "light"));

        SwingUtilities.invokeLater(() -> {
            ThemeManager.apply(dark);
            // Botones de los diálogos en español
            UIManager.put("OptionPane.okButtonText", "Aceptar");
            UIManager.put("OptionPane.cancelButtonText", "Cancelar");
            UIManager.put("OptionPane.yesButtonText", "Sí");
            UIManager.put("OptionPane.noButtonText", "No");
            new MainWindow(settings).setVisible(true);
        });
    }
}
