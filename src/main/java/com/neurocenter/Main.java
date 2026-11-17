package com.neurocenter;

import com.neurocenter.core.AppPaths;
import com.neurocenter.core.MainWindow;
import com.neurocenter.core.PropertiesStore;
import com.neurocenter.core.SingleInstance;
import com.neurocenter.core.ThemeManager;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;

public final class Main {

    public static void main(String[] args) {
        AppPaths.ensureDirectories();

        // La comprobación se realiza antes de crear cualquier ventana. Una
        // segunda ejecución activa la instancia existente y termina aquí.
        final SingleInstance singleInstance;
        try {
            singleInstance = SingleInstance.acquireOrActivate(AppPaths.root());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null,
                    "No se pudo comprobar si Center of Neurodivergence ya está abierto.\n\n"
                            + ex.getMessage(),
                    "No se pudo iniciar", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (singleInstance == null) return;

        Runtime.getRuntime().addShutdownHook(
                new Thread(singleInstance::close, "single-instance-shutdown"));

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
            MainWindow window = new MainWindow(settings);
            window.setVisible(true);
            // Se registra después de mostrar la ventana. Si llegó una orden
            // durante el arranque, SingleInstance la entrega en este momento.
            singleInstance.setActivationHandler(window::activateFromAnotherLaunch);
        });
    }
}
