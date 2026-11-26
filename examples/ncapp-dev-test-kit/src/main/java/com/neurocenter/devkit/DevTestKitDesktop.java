package com.neurocenter.devkit;

import com.neurocenter.api.AppContext;
import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.services.AgendaBridgeService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Desktop diagnostic entry point in the SAME .ncapp as DevTestKitMobile.
 * It never requests permissions or creates external side effects on startup.
 */
public final class DevTestKitDesktop implements NeuroApp {
    public static volatile boolean UNSAVED_CHANGES = false;
    private static final Set<String> DECLARED = Set.of(
            "agenda.read", "agenda.write", "network.connect", "credentials.access");
    private AppContext host;
    private JTextArea report;

    @Override public String id() { return "com.neurocenter.devtestkit"; }
    @Override public String name() { return "NeuroCenter Dev Test Kit"; }
    @Override public String version() { return "1.5.0"; }
    @Override public String description() {
        return "Diagnóstico Java 21, permisos y ciclo de vida de aplicaciones .ncapp";
    }

    @Override public void start(AppContext context) {
        host = context;
    }

    @Override public JComponent createPanel(AppContext context) {
        host = context;
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(new JLabel("Dev Test Kit 1.5.0 · Escritorio · Java 21"), BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 2, 8, 8));
        add(actions, "Java 21 y plataforma", () -> log(
                "Plataforma: " + host.platform() + "\n"
                    + "Java: " + System.getProperty("java.version", "?") + "\n"
                    + "VM: " + System.getProperty("java.vm.name", "?")));
        add(actions, "Clases y ClassLoader", () -> {
            log("Clase dependiente: " + ProbeDependency.value()
                    + "\nClassLoader: " + getClass().getClassLoader());
        });
        add(actions, "Permisos declarados", () -> {
            StringBuilder text = new StringBuilder("El manifiesto declara:\n");
            for (String permission : new LinkedHashSet<>(DECLARED))
                text.append("• ").append(permission).append(" | concedido: ")
                    .append(host.hasPermission(permission)).append("\n");
            log(text.toString());
        });
        add(actions, "Solicitar network.connect", () -> request("network.connect"));
        add(actions, "Solicitar credentials.access", () -> request("credentials.access"));
        add(actions, "Solicitar agenda.read", () -> request("agenda.read"));
        add(actions, "Solicitar agenda.write", () -> request("agenda.write"));
        add(actions, "Comprobar Agenda Bridge", () -> {
            boolean available = host.services() != null
                    && host.services().contains(AgendaBridgeService.class);
            log(available
                    ? "Agenda Bridge está registrado. Para importar tareas se requiere agenda.write."
                    : "Agenda Bridge NO está registrado. Los permisos no crean ese servicio.");
        });
        add(actions, "Consultar recurso .ncapp", () -> {
            try (var resource = getClass().getClassLoader()
                    .getResourceAsStream("dev-test-kit/probe.txt")) {
                log(resource != null
                        ? "Recurso del mismo .ncapp: OK."
                        : "ERROR: no se encontró el recurso de prueba.");
            } catch (Exception ex) {
                log("ERROR leyendo recurso: " + ex.getClass().getSimpleName());
            }
        });
        root.add(actions, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        JCheckBox unsaved = new JCheckBox("Simular cambios sin guardar (UNSAVED_CHANGES)");
        unsaved.setSelected(UNSAVED_CHANGES);
        unsaved.addActionListener(e -> {
            UNSAVED_CHANGES = unsaved.isSelected();
            log(UNSAVED_CHANGES
                    ? "Pendiente: cierra o actualiza esta app y comprueba el aviso de NeuroCenter."
                    : "Sin cambios pendientes: el host puede cerrarla sin aviso.");
        });
        bottom.add(unsaved, BorderLayout.NORTH);
        report = new JTextArea(8, 40);
        report.setEditable(false);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setText("Las pruebas son manuales; no se conceden permisos al abrir la app.\n"
                + "Instalación y actualización: usa este mismo .ncapp en Windows o Android.\n"
                + "Los permisos se piden solo al pulsar sus botones.\n");
        bottom.add(new JScrollPane(report), BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private static void add(JPanel row, String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        row.add(button);
    }

    private void request(String permission) {
        if (!DECLARED.contains(permission)) {
            log("ERROR: el kit intenta pedir un permiso no declarado: " + permission);
            return;
        }
        try {
            boolean granted = host.requestPermission(permission);
            log(permission + ": " + (granted ? "PERMITIDO" : "DENEGADO")
                    + ". NeuroCenter controla las cuatro opciones del diálogo.");
        } catch (SecurityException | UnsupportedOperationException ex) {
            log(permission + ": DENEGADO por NeuroCenter: " + ex.getMessage());
        }
    }

    private void log(String text) {
        if (report != null) {
            report.append("\n" + text + "\n");
            report.setCaretPosition(report.getDocument().getLength());
        }
    }

    @Override public void stop() {
        // The host checks UNSAVED_CHANGES before calling stop().
        // Clear it so the next test session starts without a stale warning.
        UNSAVED_CHANGES = false;
        report = null;
        host = null;
    }
}
