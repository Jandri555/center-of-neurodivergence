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
    private final Map<String, NeuroApp> apps = new LinkedHashMap<>();
    private final Set<String> externalAppIds = new HashSet<>();
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

        // 1) Apps integradas en el propio .jar
        register(new AgendaApp(), false);
        register(new PdfReaderApp(), false);

        // 2) Apps externas: .jar en AppData/CenterOfNeurodivergence/apps
        AppLoader.Result apps = AppLoader.load(AppPaths.appsDir());
        loadErrors.addAll(apps.errors());
        for (NeuroApp p : apps.apps()) register(p, true);

        startApps();
        buildUi();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
        setMinimumSize(new Dimension(960, 620));
        setSize(1240, 800);
        setLocationRelativeTo(null);
        showApp(HOME);

        if (!loadErrors.isEmpty()) SwingUtilities.invokeLater(this::showLoadErrors);
    }

    // ------------------------------------------------------------------ registro y arranque

    private void register(NeuroApp app, boolean external) {
        try {
            String id = app.id();
            String name = app.name();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("el id está vacío");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("el nombre está vacío");
            if (apps.containsKey(id)) {
                loadErrors.add("Hay dos apps con el id «" + id + "»: se ignora «" + name + "».");
                return;
            }
            apps.put(id, app);
            if (external) externalAppIds.add(id);
        } catch (Throwable t) {
            loadErrors.add(app.getClass().getName() + ": " + t);
        }
    }

    private void startApps() {
        for (NeuroApp p : new ArrayList<>(apps.values())) {
            AppContext ctx = new Ctx(p);
            contexts.put(p.id(), ctx);
            try {
                p.start(ctx);
            } catch (Throwable t) {
                loadErrors.add(p.name() + ": error al iniciar (" + t + ")");
                apps.remove(p.id());
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
        for (NeuroApp p : apps.values()) {
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

        settingsButton = new JButton("⚙");
        settingsButton.setToolTipText("Ajustes generales");
        settingsButton.setFont(settingsButton.getFont().deriveFont(Font.PLAIN, 25f));
        settingsButton.setPreferredSize(new Dimension(48, 44));
        settingsButton.setMargin(new Insets(4, 8, 4, 8));
        settingsButton.setFocusable(false);
        settingsButton.addActionListener(e -> openSettings());
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        topBar.add(settingsButton);
        add(topBar, BorderLayout.NORTH);

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
        for (NeuroApp p : apps.values()) {
            addNav(side, p.id(), p.name(), iconFor(p, 24));
        }

        side.add(Box.createVerticalGlue());

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
        b.addActionListener(e -> showApp(id));
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
        for (NeuroApp p : apps.values()) grid.add(buildCard(p));
        JPanel gridTop = new JPanel(new BorderLayout());
        gridTop.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(gridTop);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html>Para instalar o desinstalar apps, utiliza el gestor de <b>"
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

        String kind = externalAppIds.contains(p.id()) ? "App externa, versión " + safeVersion(p) : "Integrado";
        JLabel text = new JLabel("<html><body style='width:220px'><b style='font-size:13pt'>" + esc(p.name())
                + "</b><br>" + esc(p.description())
                + "<br><br><span style='color:gray'>" + esc(kind) + "</span></body></html>");
        text.setVerticalAlignment(SwingConstants.TOP);

        JButton open = new JButton("Abrir");
        open.addActionListener(e -> showApp(p.id()));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(12, 0, 0, 0));
        south.add(open);

        card.add(icon, BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showApp(p.id()); }
            @Override public void mouseEntered(MouseEvent e) { card.setHighlighted(true); }
            @Override public void mouseExited(MouseEvent e) { card.setHighlighted(false); }
        });
        return card;
    }

    private void showApp(String id) {
        if (!HOME.equals(id) && !openedPanels.contains(id)) {
            NeuroApp p = apps.get(id);
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
        String[] options = {"Cambiar tema", "Gestionar temas", "Gestionar apps", "Cerrar"};
        int choice = JOptionPane.showOptionDialog(this, "Ajustes generales", "Ajustes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            chooseTheme();
        } else if (choice == 1) {
            manageThemes();
        } else if (choice == 2) {
            manageApps();
        }
    }

    private void chooseTheme() {
        List<ThemeManager.ThemeInfo> themes = ThemeManager.availableThemes();
        String[] names = themes.stream().map(ThemeManager.ThemeInfo::name).toArray(String[]::new);
        String currentId = appSettings.get("theme", "light");
        int selected = 0;
        for (int i = 0; i < themes.size(); i++) {
            if (themes.get(i).id().equals(currentId)) {
                selected = i;
                break;
            }
        }

        int choice = JOptionPane.showOptionDialog(this,
                "Selecciona el aspecto de la aplicación:", "Temas",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, names, names[selected]);
        if (choice >= 0 && choice < themes.size()) {
            String themeId = themes.get(choice).id();
            if (!themeId.equals(currentId)) {
                appSettings.put("theme", themeId);
                ThemeManager.apply(themeId);
                SwingUtilities.updateComponentTreeUI(this);
                for (Window window : getOwnedWindows()) {
                    SwingUtilities.updateComponentTreeUI(window);
                }
            }
        }
    }

    private void manageThemes() {
        String[] options = {"Instalar tema (.tme)", "Desinstalar tema", "Abrir carpeta de temas", "Volver"};
        int choice = JOptionPane.showOptionDialog(this,
                "Los temas son paquetes de apariencia. Solo se admiten archivos .tme con JSON y recursos; no ejecutan código.",
                "Gestor de temas", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Temas (*.tme)", "tme"));
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                try {
                    String id = ThemeArchive.install(chooser.getSelectedFile().toPath());
                    JOptionPane.showMessageDialog(this, "Tema «" + id + "» instalado. Ya puedes seleccionarlo en Cambiar tema.");
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo instalar el tema: " + ex.getMessage(),
                            "Error al instalar", JOptionPane.ERROR_MESSAGE);
                }
            }
        } else if (choice == 1) {
            List<ThemeManager.ThemeInfo> installed = ThemeManager.availableThemes().stream()
                    .filter(theme -> !theme.builtIn()).toList();
            if (installed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No hay temas externos instalados.");
                return;
            }
            ThemeManager.ThemeInfo[] themes = installed.toArray(ThemeManager.ThemeInfo[]::new);
            ThemeManager.ThemeInfo selected = (ThemeManager.ThemeInfo) JOptionPane.showInputDialog(
                    this, "Selecciona el tema que quieres desinstalar:", "Desinstalar tema",
                    JOptionPane.WARNING_MESSAGE, null, themes, themes[0]);
            if (selected != null && JOptionPane.showConfirmDialog(this,
                    "Se eliminará el tema «" + selected.name() + "». ¿Continuar?",
                    "Confirmar desinstalación", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                try {
                    boolean active = selected.id().equals(appSettings.get("theme", "light"));
                    ThemeArchive.uninstall(selected.id());
                    if (active) {
                        appSettings.put("theme", "light");
                        ThemeManager.apply("light");
                        SwingUtilities.updateComponentTreeUI(this);
                    }
                    JOptionPane.showMessageDialog(this, "Tema desinstalado.");
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo desinstalar el tema: " + ex.getMessage(),
                            "Error al desinstalar", JOptionPane.ERROR_MESSAGE);
                }
            }
        } else if (choice == 2) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(AppPaths.themesDir().toFile());
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(this, "No se pudo abrir la carpeta: " + ex.getMessage());
            }
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
        Icon fallback;
        try {
            fallback = p.icon();
            if (fallback == null) fallback = new BadgeIcon(p.name(), p.id(), size);
        } catch (Throwable ignored) {
            // una app con un icono roto no debe romper el panel
            fallback = new BadgeIcon(p.name(), p.id(), size);
        }
        return ThemeManager.appIcon(p.id(), fallback, size);
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
        private final NeuroApp app;
        private PropertiesStore store;

        Ctx(NeuroApp app) { this.app = app; }

        @Override public Path dataDir() { return AppPaths.appDataDir(app.id()); }

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

        @Override public void openApp(String appId) {
            if (apps.containsKey(appId)) showApp(appId);
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
