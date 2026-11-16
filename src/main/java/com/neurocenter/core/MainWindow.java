package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;
import com.neurocenter.apps.agenda.AgendaApp;
import com.neurocenter.apps.pdfreader.PdfReaderApp;
import com.neurocenter.ui.BadgeIcon;
import com.neurocenter.ui.RoundedPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Panel principal: menú lateral con todos los apps y una pantalla de inicio.
 * Los apps integrados se registran en el constructor; los apps se leen de AppData.
 */
public final class MainWindow extends JFrame {

    private static final String HOME = "__home__";

    private final PropertiesStore appSettings;
    private final Map<String, NeuroApp> programs = new LinkedHashMap<>();
    private final Set<String> pluginIds = new HashSet<>();
    private final Map<String, AppContext> contexts = new HashMap<>();
    private final Map<String, JToggleButton> navButtons = new HashMap<>();
    private final Set<String> openedPanels = new HashSet<>();
    private final List<String> loadErrors = new ArrayList<>();
    private final ButtonGroup navGroup = new ButtonGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel statusLabel = new JLabel(" ");
    private JButton settingsButton;

    public MainWindow(PropertiesStore appSettings) {
        super(AppInfo.NAME);
        this.appSettings = appSettings;

        // 1) Programas integrados en el propio .jar
        register(new AgendaApp(), false);
        register(new PdfReaderApp(), false);

        // 2) Programas externos: .jar en AppData/CenterOfNeurodivergence/apps
        AppLoader.Result apps = AppLoader.load(AppPaths.appsDir());
        loadErrors.addAll(apps.errors());
        for (NeuroApp p : apps.programs()) register(p, true);

        startPrograms();
        buildUi();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
        setMinimumSize(new Dimension(960, 620));
        setSize(1240, 800);
        setLocationRelativeTo(null);
        showProgram(HOME);

        if (!loadErrors.isEmpty()) SwingUtilities.invokeLater(this::showLoadErrors);
    }

    // ------------------------------------------------------------------ registro y arranque

    private void register(NeuroApp program, boolean plugin) {
        try {
            String id = program.id();
            String name = program.name();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("el id está vacío");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("el nombre está vacío");
            if (programs.containsKey(id)) {
                loadErrors.add("Hay dos apps con el id «" + id + "»: se ignora «" + name + "».");
                return;
            }
            programs.put(id, program);
            if (plugin) pluginIds.add(id);
        } catch (Throwable t) {
            loadErrors.add(program.getClass().getName() + ": " + t);
        }
    }

    private void startPrograms() {
        for (NeuroApp p : new ArrayList<>(programs.values())) {
            AppContext ctx = new Ctx(p);
            contexts.put(p.id(), ctx);
            try {
                p.start(ctx);
            } catch (Throwable t) {
                loadErrors.add(p.name() + ": error al iniciar (" + t + ")");
                programs.remove(p.id());
                contexts.remove(p.id());
            }
        }
    }

    private void showLoadErrors() {
        StringBuilder sb = new StringBuilder("Algunos apps no se han podido cargar:\n");
        for (String e : loadErrors) sb.append("\n• ").append(e);
        JOptionPane.showMessageDialog(this, sb.toString(), AppInfo.NAME, JOptionPane.WARNING_MESSAGE);
    }

    private void shutdown() {
        for (NeuroApp p : programs.values()) {
            try {
                p.stop();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ interfaz

    private void buildUi() {
        setLayout(new BorderLayout());
        add(buildSidebar(), BorderLayout.WEST);

        content.add(buildHome(), HOME);
        add(content, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(5, 14, 5, 14));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(statusBar, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(22, 12, 14, 12));
        side.setPreferredSize(new Dimension(236, 10));

        JLabel title = new JLabel("<html><b>" + AppInfo.NAME.replace(" of ", " of<br>") + "</b></html>");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        title.setBorder(new EmptyBorder(0, 10, 0, 0));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(title);
        side.add(Box.createVerticalStrut(22));

        addNav(side, HOME, "Inicio", new BadgeIcon("I", HOME, 24));
        for (NeuroApp p : programs.values()) {
            addNav(side, p.id(), p.name(), iconFor(p, 24));
        }

        side.add(Box.createVerticalGlue());

        settingsButton = new JButton("⚙  Ajustes");
        settingsButton.setToolTipText("Ajustes generales y gestión de apps");
        settingsButton.addActionListener(e -> openSettings());
        settingsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, settingsButton.getPreferredSize().height));
        side.add(settingsButton);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(side, BorderLayout.CENTER);
        wrapper.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.EAST);
        return wrapper;
    }

    private void addNav(JPanel side, String id, String text, Icon icon) {
        JToggleButton b = new JToggleButton(text, icon);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setIconTextGap(12);
        b.setMargin(new Insets(8, 10, 8, 10));
        b.putClientProperty("JButton.buttonType", "toolBarButton"); // estilo plano de FlatLaf
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height + 4));
        b.addActionListener(e -> showProgram(id));
        navGroup.add(b);
        navButtons.put(id, b);
        side.add(b);
        side.add(Box.createVerticalStrut(2));
    }

    private JComponent buildHome() {
        JPanel root = new JPanel(new BorderLayout(0, 24));
        root.setBorder(new EmptyBorder(36, 44, 24, 44));

        JLabel heading = new JLabel("<html><div style='font-size:22pt'><b>Tus apps</b></div>"
                + "<div style='margin-top:6px'>Elige uno para empezar.</div></html>");
        root.add(heading, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 2, 16, 16));
        for (NeuroApp p : programs.values()) grid.add(buildCard(p));
        JPanel gridTop = new JPanel(new BorderLayout());
        gridTop.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(gridTop);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html>Para añadir más apps, copia su archivo .jar en <b>"
                + esc(AppPaths.appsDir().toString()) + "</b> y reinicia la aplicación.</html>");
        root.add(hint, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildCard(NeuroApp p) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(16, 0), 18);
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel icon = new JLabel(iconFor(p, 40));
        icon.setVerticalAlignment(SwingConstants.TOP);

        String kind = pluginIds.contains(p.id()) ? "Plugin, versión " + safeVersion(p) : "Integrado";
        JLabel text = new JLabel("<html><body style='width:220px'><b style='font-size:13pt'>" + esc(p.name())
                + "</b><br>" + esc(p.description())
                + "<br><br><span style='color:gray'>" + esc(kind) + "</span></body></html>");
        text.setVerticalAlignment(SwingConstants.TOP);

        JButton open = new JButton("Abrir");
        open.addActionListener(e -> showProgram(p.id()));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(12, 0, 0, 0));
        south.add(open);

        card.add(icon, BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showProgram(p.id()); }
            @Override public void mouseEntered(MouseEvent e) { card.setHighlighted(true); }
            @Override public void mouseExited(MouseEvent e) { card.setHighlighted(false); }
        });
        return card;
    }

    private void showProgram(String id) {
        if (!HOME.equals(id) && !openedPanels.contains(id)) {
            NeuroApp p = programs.get(id);
            if (p == null) return;
            JComponent panel;
            try {
                panel = p.createPanel(contexts.get(id));
                if (panel == null) throw new IllegalStateException("createPanel devolvió null");
            } catch (Throwable t) {
                panel = errorPanel(p, t);
            }
            content.add(panel, id);
            openedPanels.add(id);
        }
        cards.show(content, id);
        JToggleButton b = navButtons.get(id);
        if (b != null) b.setSelected(true);
        statusLabel.setText(" ");
    }

    private JComponent errorPanel(NeuroApp p, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        JTextArea area = new JTextArea("«" + p.name() + "» no se ha podido abrir.\n\n" + sw);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setBorder(new EmptyBorder(20, 20, 20, 20));
        JScrollPane pane = new JScrollPane(area);
        pane.setBorder(BorderFactory.createEmptyBorder());
        return pane;
    }

    // ------------------------------------------------------------------ acciones

    private void openSettings() {
        String[] options = {"Tema claro", "Tema oscuro", "Gestionar apps", "Cerrar"};
        int choice = JOptionPane.showOptionDialog(this, "Ajustes generales", "Ajustes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0 || choice == 1) {
            appSettings.put("theme", choice == 1 ? "dark" : "light");
            ThemeManager.apply(choice == 1);
            SwingUtilities.updateComponentTreeUI(this);
            for (Window window : getOwnedWindows()) SwingUtilities.updateComponentTreeUI(window);
        } else if (choice == 2) {
            manageApps();
        }
    }

    private void manageApps() {
        String[] options = {"Instalar app (.jar)", "Desinstalar app", "Abrir carpeta de apps", "Volver"};
        int choice = JOptionPane.showOptionDialog(this,
                "Las operaciones se aplican al reiniciar. Instala solo apps de fuentes de confianza.",
                "Gestor de apps", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Apps Java (*.jar)", "jar"));
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                java.nio.file.Path source = chooser.getSelectedFile().toPath();
                try {
                    java.nio.file.Path target = AppPaths.appsDir().resolve(source.getFileName());
                    if (java.nio.file.Files.exists(target)) throw new java.io.IOException("Ya existe una app con ese nombre.");
                    java.nio.file.Files.copy(source, target);
                    JOptionPane.showMessageDialog(this, "App instalada. Estará disponible al reiniciar.");
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo instalar la app: " + ex.getMessage());
                }
            }
        } else if (choice == 1) {
            try (var stream = java.nio.file.Files.list(AppPaths.appsDir())) {
                String[] jars = stream.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                        .map(p -> p.getFileName().toString()).toArray(String[]::new);
                if (jars.length == 0) {
                    JOptionPane.showMessageDialog(this, "No hay apps externas instaladas.");
                    return;
                }
                String selected = (String) JOptionPane.showInputDialog(this, "Selecciona una app:",
                        "Desinstalar app", JOptionPane.WARNING_MESSAGE, null, jars, jars[0]);
                if (selected != null && JOptionPane.showConfirmDialog(this,
                        "Se eliminará al próximo inicio. ¿Continuar?", "Confirmar",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    java.nio.file.Files.writeString(AppPaths.root().resolve("pending-uninstall.txt"),
                            selected + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    JOptionPane.showMessageDialog(this, "Desinstalación programada para el próximo inicio.");
                }
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(this, "No se pudo gestionar la app: " + ex.getMessage());
            }
        } else if (choice == 2) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(AppPaths.appsDir().toFile());
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(this, "No se pudo abrir la carpeta: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static Icon iconFor(NeuroApp p, int size) {
        try {
            Icon icon = p.icon();
            if (icon != null) return icon;
        } catch (Throwable ignored) {
            // un plugin con un icono roto no debe romper el panel
        }
        return new BadgeIcon(p.name(), p.id(), size);
    }

    private static String safeVersion(NeuroApp p) {
        try {
            return p.version();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Lo que cada app recibe del panel. */
    private final class Ctx implements AppContext {
        private final NeuroApp program;
        private PropertiesStore store;

        Ctx(NeuroApp program) { this.program = program; }

        @Override public Path dataDir() { return AppPaths.appDataDir(program.id()); }

        private synchronized PropertiesStore store() {
            if (store == null) store = new PropertiesStore(dataDir().resolve("settings.properties"));
            return store;
        }

        @Override public String getSetting(String key, String defaultValue) { return store().get(key, defaultValue); }

        @Override public void putSetting(String key, String value) { store().put(key, value); }

        @Override public void setStatus(String message) {
            String text = message == null || message.isBlank() ? " " : message;
            if (SwingUtilities.isEventDispatchThread()) statusLabel.setText(text);
            else SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }

        @Override public void openApp(String programId) {
            if (programs.containsKey(programId)) showProgram(programId);
        }

        @Override public Window window() { return MainWindow.this; }

        @Override public void requestAttention() {
            Toolkit.getDefaultToolkit().beep();
            if ((getExtendedState() & Frame.ICONIFIED) != 0) setExtendedState(Frame.NORMAL);
            toFront();
            requestFocus();
        }
    }
}
