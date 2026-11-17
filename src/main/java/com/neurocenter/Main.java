package com.neurocenter;

import com.neurocenter.core.AppPaths;
import com.neurocenter.core.MainWindow;
import com.neurocenter.core.PropertiesStore;
import com.neurocenter.core.ThemeManager;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {

    public static void main(String[] args) {
        AppPaths.ensureDirectories();
        PropertiesStore settings = new PropertiesStore(AppPaths.root().resolve("settings.properties"));
        String themeId = settings.get("theme", "light");

        SwingUtilities.invokeLater(() -> {
            try {
                ThemeManager.apply(themeId);
            } catch (IllegalArgumentException ex) {
                // Puede ocurrir si la preferencia procede de otro dispositivo
                // y apunta a un tema solo móvil.
                settings.put("theme", "light");
                ThemeManager.apply("light");
                JOptionPane.showMessageDialog(null, ex.getMessage()
                                + "\n\nSe ha restaurado el tema Claro.",
                        "Tema no compatible", JOptionPane.ERROR_MESSAGE);
            }

            // En Windows 10/11, FlatLaf dibuja la barra de título con el tema
            // de la aplicación y conserva el borde nativo para redimensionar,
            // ajustar ventanas a los bordes y usar las animaciones del sistema.
            // Activarlo antes de crear cualquier JFrame o JDialog.
            if (FlatLaf.supportsNativeWindowDecorations()) {
                JFrame.setDefaultLookAndFeelDecorated(true);
                JDialog.setDefaultLookAndFeelDecorated(true);
            }

            // Botones de los diálogos en español
            UIManager.put("OptionPane.okButtonText", "Aceptar");
            UIManager.put("OptionPane.cancelButtonText", "Cancelar");
            UIManager.put("OptionPane.yesButtonText", "Sí");
            UIManager.put("OptionPane.noButtonText", "No");
            new MainWindow(settings).setVisible(true);
        });
    }
}
