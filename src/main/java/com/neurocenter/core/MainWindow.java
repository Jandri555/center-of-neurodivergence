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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagLayout;
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Panel principal: menú lateral con todos los apps y una pantalla de inicio.
 * Los apps integrados se registran en el constructor; los apps se leen de AppData.
 */
public final class MainWindow extends JFrame {

    private static final String HOME = "__home__";

    private final PropertiesStore appSettings;
    private AppRegistry appRegistry;
    private final Map<String, NeuroApp> apps = new LinkedHashMap<>();
    private final Set<String> externalAppIds = new HashSet<>();
    private final Map<String, String> externalJarByAppId = new HashMap<>();
    private final Map<String, JToggleButton> navButtons = new HashMap<>();
    private final Map<String, JPanel> navMarkers = new HashMap<>();
    private final Map<String, JPanel> navRows = new HashMap<>();
    private final Map<String, AppSession> appSessions = new LinkedHashMap<>();
    private final JPanel openAppsList = new JPanel();
    private String activeAppId = HOME;
    private boolean shuttingDown;
    private final List<String> loadErrors = new ArrayList<>();
    private final ButtonGroup navGroup = new ButtonGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel statusLabel = new JLabel(" ");
    private JButton settingsButton;

    public MainWindow(PropertiesStore appSettings) {
        super(AppInfo.NAME);
        // En Windows 10/11 FlatLaf gestiona la barra de título mediante sus
        // decoraciones nativas. No marcar el JFrame como undecorated manualmente:
        // eso elimina el marco redondeado y el comportamiento de ventana de Windows.
        this.appSettings = appSettings;

        // 1) Apps integradas en el propio .jar
        register(new AgendaApp(), false);
        register(new PdfReaderApp(), false);

        // 2) Apps externas: solo se cargan los JAR inscritos en el registro SQLite.
        Set<String> autorunJars = new HashSet<>();
        try {
            appRegistry = new AppRegistry(AppPaths.root().resolve("apps.db"));
            AppLoader.Result externalApps = AppLoader.load(
                    AppPaths.appsDir(), appRegistry.installedJarFilenames());
            loadErrors.addAll(externalApps.errors());
            autorunJars.addAll(appRegistry.autorunJarFilenames());
            for (AppLoader.LoadedApp loaded : externalApps.apps()) {
                register(loaded.app(), true, loaded.jarFilename());
            }
        } catch (Exception ex) {
            loadErrors.add("No se pudo abrir el registro de aplicaciones (apps.db): " + ex.getMessage());
        }

        buildUi();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
        setMinimumSize(new Dimension(960, 620));
        setSize(1240, 800);
        setLocationRelativeTo(null);
        showApp(HOME);

        // Las apps con autorun arrancan en segundo plano, pero Inicio permanece
        // como pantalla activa al abrir el panel.
        for (Map.Entry<String, String> entry : externalJarByAppId.entrySet()) {
            if (autorunJars.contains(entry.getValue())) launchApp(entry.getKey(), false);
        }

        if (!loadErrors.isEmpty()) SwingUtilities.invokeLater(this::showLoadErrors);
    }

    // ------------------------------------------------------------------ registro y arranque

    private void register(NeuroApp app, boolean external, String jarFilename) {
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
            if (external) {
                externalAppIds.add(id);
                externalJarByAppId.put(id, jarFilename);
            }
        } catch (Throwable t) {
            loadErrors.add(app.getClass().getName() + ": " + t);
        }
    }

    private void showLoadErrors() {
        StringBuilder sb = new StringBuilder("Algunos apps no se han podido cargar:\n");
        for (String e : loadErrors) sb.append("\n• ").append(e);
        JOptionPane.showMessageDialog(this, sb.toString(), AppInfo.NAME, JOptionPane.WARNING_MESSAGE);
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;

        List<AppSession> sessions = new ArrayList<>(appSessions.values());
        for (AppSession session : sessions) {
            if (session.closing) continue;
            session.closing = true;
            try {
                // Cada app termina en su propio hilo, después de cualquier start()
                // que pudiera seguir ejecutándose en esa misma cola.
                session.worker.execute(() -> {
                    try {
                        session.app.stop();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        session.worker.shutdown();
                    }
                });
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                session.worker.shutdownNow();
            }
        }

        dispose();
        Thread terminator = new Thread(() -> {
            for (AppSession session : sessions) {
                try {
                    if (!session.worker.awaitTermination(10, TimeUnit.SECONDS)) {
                        session.worker.shutdownNow();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    session.worker.shutdownNow();
                }
            }
            System.exit(0);
        }, "app-shutdown");
        terminator.setDaemon(false);
        terminator.start();
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
        settingsButton.putClientProperty("JButton.buttonType", "toolBarButton");
        settingsButton.setOpaque(false);
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

        JLabel version = new JLabel("Versión " + AppInfo.VERSION);
        version.setFont(title.getFont().deriveFont(Font.PLAIN, 11f));
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted != null) version.setForeground(muted);
        version.setBorder(new EmptyBorder(2, 10, 0, 0));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(version);
        side.add(Box.createVerticalStrut(22));

        // Inicio siempre está visible. Las apps aparecen debajo solo mientras
        // tienen una sesión abierta.
        addNav(side, HOME, "Inicio", new BadgeIcon("I", HOME, 24), false);
        side.add(Box.createVerticalStrut(10));

        openAppsList.setLayout(new BoxLayout(openAppsList, BoxLayout.Y_AXIS));
        openAppsList.setOpaque(false);
        openAppsList.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(openAppsList);
        side.add(Box.createVerticalGlue());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(side, BorderLayout.CENTER);
        wrapper.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.EAST);
        return wrapper;
    }

    private void addNav(JPanel parent, String id, String text, Icon icon, boolean closable) {
        JToggleButton button = new JToggleButton(text, icon);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setMargin(new Insets(8, 10, 8, 10));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setBorder(new EmptyBorder(8, 12, 8, 10));
        button.addActionListener(e -> showApp(id));

        JPanel marker = new JPanel();
        marker.setOpaque(true);
        marker.setPreferredSize(new Dimension(3, 1));
        marker.setMinimumSize(new Dimension(3, 0));
        marker.setMaximumSize(new Dimension(3, Integer.MAX_VALUE));
        marker.setBackground(navMarkerColor(false));

        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        controls.add(button, BorderLayout.CENTER);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(marker, BorderLayout.WEST);
        row.add(controls, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (closable) {
            JButton close = new JButton("×");
            close.setToolTipText("Cerrar " + text);
            close.setFocusable(false);
            close.setOpaque(false);
            close.setContentAreaFilled(false);
            close.setBorder(new EmptyBorder(4, 8, 4, 8));
            close.setMargin(new Insets(0, 0, 0, 0));
            close.putClientProperty("JButton.buttonType", "toolBarButton");
            close.addActionListener(e -> requestCloseApp(id));
            controls.add(close, BorderLayout.EAST);
            navRows.put(id, row);
        }

        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height + 4));
        navGroup.add(button);
        navButtons.put(id, button);
        navMarkers.put(id, marker);
        parent.add(row);
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

        JLabel hint = new JLabel("<html>Puedes instalar o desinstalar apps desde <b>Ajustes generales → Gestionar apps</b>.</html>");
        root.add(hint, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildCard(NeuroApp p) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(16, 0), 18);
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel icon = new JLabel(iconFor(p, 40));
        icon.putClientProperty("theme.appId", p.id());
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
        if (HOME.equals(id)) {
            activeAppId = HOME;
            cards.show(content, HOME);
            JToggleButton home = navButtons.get(HOME);
            if (home != null) home.setSelected(true);
            updateNavAppearance();
            statusLabel.setText(" ");
            return;
        }
        launchApp(id, true);
    }

    /** Inicia una app; activate=false permite arrancarla sin abandonar Inicio. */
    private void launchApp(String id, boolean activate) {
        NeuroApp app = apps.get(id);
        if (app == null) return;

        AppSession existing = appSessions.get(id);
        if (existing != null) {
            if (existing.closing) {
                if (activate) statusLabel.setText("La aplicación se está cerrando…");
                return;
            }
            if (activate) {
                activeAppId = id;
                cards.show(content, id);
                JToggleButton button = navButtons.get(id);
                if (button != null) button.setSelected(true);
                updateNavAppearance();
                statusLabel.setText(" ");
            }
            return;
        }

        AppSession session = new AppSession(id, app, new Ctx(app));
        appSessions.put(id, session);

        JPanel loading = new JPanel(new GridBagLayout());
        loading.add(new JLabel("Iniciando " + app.name() + "…"));
        session.panel = loading;
        content.add(loading, id);

        addNav(openAppsList, id, app.name(), iconFor(app, 24), true);
        if (activate) {
            activeAppId = id;
            cards.show(content, id);
            JToggleButton button = navButtons.get(id);
            if (button != null) button.setSelected(true);
            statusLabel.setText(" ");
        }
        updateNavAppearance();

        // Cada app dispone de una cola/hilo propio para su ciclo de vida.
        // La creación y manipulación de componentes Swing sigue reservada al EDT.
        session.worker.execute(() -> {
            Throwable failure = null;
            try {
                app.start(session.context);
            } catch (Throwable t) {
                failure = t;
            }
            Throwable startFailure = failure;
            SwingUtilities.invokeLater(() -> finishOpeningApp(session, startFailure));
        });
    }

    private void finishOpeningApp(AppSession session, Throwable startFailure) {
        if (appSessions.get(session.id) != session || session.closing) return;

        if (startFailure != null) {
            setAppPanel(session, errorPanel(session.app, startFailure));
            statusLabel.setText("No se pudo iniciar " + session.app.name() + ".");
            return;
        }

        try {
            JComponent panel = session.app.createPanel(session.context);
            if (panel == null) throw new IllegalStateException("createPanel devolvió null");
            setAppPanel(session, panel);
        } catch (Throwable t) {
            setAppPanel(session, errorPanel(session.app, t));
            statusLabel.setText("No se pudo abrir " + session.app.name() + ".");
        }
    }

    private void setAppPanel(AppSession session, JComponent panel) {
        if (session.panel != null) content.remove(session.panel);
        session.panel = panel;
        content.add(panel, session.id);
        content.revalidate();
        content.repaint();
        if (session.id.equals(activeAppId)) cards.show(content, session.id);
    }

    private void requestCloseApp(String id) {
        AppSession session = appSessions.get(id);
        if (session == null || session.closing) return;

        if (hasUnsavedChanges(session.app)) {
            String[] options = { "Aceptar", "Cerrar de todos modos" };
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "Esta aplicación indica que puede tener cambios sin guardar.\n\n"
                            + "Pulsa «Aceptar» para mantenerla abierta o «Cerrar de todos modos» para finalizarla.",
                    "Posibles cambios sin guardar",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]);
            if (choice != 1) return;
        }

        closeApp(session);
    }

    private void closeApp(AppSession session) {
        if (session.closing) return;
        session.closing = true;

        if (session.id.equals(activeAppId)) showApp(HOME);

        if (session.panel != null) content.remove(session.panel);
        content.revalidate();
        content.repaint();

        JToggleButton button = navButtons.remove(session.id);
        if (button != null) navGroup.remove(button);
        navMarkers.remove(session.id);

        JPanel row = navRows.remove(session.id);
        if (row != null) openAppsList.remove(row);
        openAppsList.revalidate();
        openAppsList.repaint();
        updateNavAppearance();

        statusLabel.setText("Cerrando " + session.app.name() + "…");

        // stop() se ejecuta en el mismo hilo de la app y después de start().
        session.worker.execute(() -> {
            try {
                session.app.stop();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                session.worker.shutdown();
                SwingUtilities.invokeLater(() -> {
                    if (appSessions.get(session.id) == session) {
                        appSessions.remove(session.id);
                    }
                    if (!shuttingDown
                            && statusLabel.getText().equals("Cerrando " + session.app.name() + "…")) {
                        statusLabel.setText(" ");
                    }
                });
            }
        });
    }

    /**
     * Convención opcional para los apps: public static volatile boolean
     * UNSAVED_CHANGES. Si no existe, o vale false, el cierre no pide confirmación.
     */
    private static boolean hasUnsavedChanges(NeuroApp app) {
        for (Class<?> type = app.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            final Field field;
            try {
                field = type.getDeclaredField("UNSAVED_CHANGES");
            } catch (NoSuchFieldException ignored) {
                continue;
            }

            if (field.getType() != boolean.class && field.getType() != Boolean.class) return true;
            try {
                if (!field.trySetAccessible()) return true;
                Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : app);
                return Boolean.TRUE.equals(value);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Si la app declara la flag pero no podemos leerla, priorizamos
                // no perder datos y mostramos la advertencia.
                return true;
            }
        }
        return false;
    }

    private void updateNavAppearance() {
        for (Map.Entry<String, JToggleButton> entry : navButtons.entrySet()) {
            JPanel marker = navMarkers.get(entry.getKey());
            if (marker != null) {
                marker.setBackground(navMarkerColor(entry.getValue().isSelected()));
            }
            entry.getValue().repaint();
        }
    }

    private Color navMarkerColor(boolean selected) {
        Color panel = UIManager.getColor("Panel.background");
        if (!selected) return panel != null ? panel : getBackground();

        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) accent = UIManager.getColor("Component.focusColor");
        if (accent == null) accent = UIManager.getColor("Button.foreground");
        return accent != null ? accent : getForeground();
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
        // Diálogo propio en lugar de JOptionPane: este último asigna foco
        // automáticamente al primer botón y FlatLaf lo resalta como predeterminado.
        JDialog dialog = new JDialog(this, "Ajustes", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBorder(new EmptyBorder(18, 18, 14, 18));

        JLabel message = new JLabel("Ajustes generales");
        root.add(message, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 4, 8, 0));
        String[] labels = {"Cambiar tema", "Gestionar temas", "Gestionar apps", "Cerrar"};
        Runnable[] actions = {
                this::chooseTheme,
                this::manageThemes,
                this::manageApps,
                dialog::dispose
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            JButton button = new JButton(labels[i]);
            // Sin foco inicial: todos los botones conservan el mismo aspecto neutro.
            button.setFocusable(false);
            button.addActionListener(e -> {
                dialog.dispose();
                actions[index].run();
            });
            buttons.add(button);
        }

        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(500, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
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
                refreshThemeIcons();
            }
        }
    }

    private void refreshThemeIcons() {
        for (Map.Entry<String, JToggleButton> entry : navButtons.entrySet()) {
            NeuroApp app = apps.get(entry.getKey());
            if (app != null) entry.getValue().setIcon(iconFor(app, 24));
        }
        updateNavAppearance();
        refreshThemeIcons(this);
    }

    private void refreshThemeIcons(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel label
                    && label.getClientProperty("theme.appId") instanceof String appId) {
                NeuroApp app = apps.get(appId);
                if (app != null) label.setIcon(iconFor(app, 40));
            }
            if (component instanceof Container nested) refreshThemeIcons(nested);
        }
    }

    private void manageThemes() {
        String[] options = {"Instalar tema (.tme)", "Desinstalar tema", "Volver"};
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
                        refreshThemeIcons();
                    }
                    JOptionPane.showMessageDialog(this, "Tema desinstalado.");
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo desinstalar el tema: " + ex.getMessage(),
                            "Error al desinstalar", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void manageApps() {
        if (appRegistry == null) {
            JOptionPane.showMessageDialog(this, "El registro SQLite de aplicaciones no está disponible.",
                    "Gestionar apps", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String[] options = {"Instalar app (.jar)", "Configurar inicio automático", "Desinstalar app", "Volver"};
        int choice = JOptionPane.showOptionDialog(this,
                "Gestiona las aplicaciones externas instaladas. Los cambios de instalación y desinstalación "
                        + "se aplican al reiniciar.",
                "Gestor de apps", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);

        if (choice == 0) {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Apps Java (*.jar)", "jar"));
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                java.nio.file.Path source = chooser.getSelectedFile().toPath();
                java.nio.file.Path target = AppPaths.appsDir().resolve(source.getFileName());
                try {
                    if (java.nio.file.Files.exists(target)) {
                        throw new java.io.IOException("Ya existe un archivo con ese nombre.");
                    }
                    java.nio.file.Files.copy(source, target);
                    try {
                        appRegistry.registerJar(target);
                    } catch (Exception ex) {
                        java.nio.file.Files.deleteIfExists(target);
                        throw ex;
                    }
                    JOptionPane.showMessageDialog(this,
                            "App instalada. Estará disponible al reiniciar.\n"
                                    + "El inicio automático se puede configurar desde este mismo gestor.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo instalar la app: " + ex.getMessage(),
                            "Error al instalar", JOptionPane.ERROR_MESSAGE);
                }
            }
        } else if (choice == 1) {
            configureAppAutorun();
        } else if (choice == 2) {
            uninstallExternalApp();
        }
    }

    private void configureAppAutorun() {
        try {
            List<AppRegistry.AppRecord> installed = appRegistry.listApps();
            if (installed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No hay apps externas instaladas.");
                return;
            }
            AppRegistry.AppRecord selected = (AppRegistry.AppRecord) JOptionPane.showInputDialog(
                    this, "Selecciona la aplicación:", "Inicio automático",
                    JOptionPane.QUESTION_MESSAGE, null,
                    installed.toArray(AppRegistry.AppRecord[]::new), installed.get(0));
            if (selected == null) return;

            boolean enable = !selected.autorun();
            int answer = JOptionPane.showConfirmDialog(this,
                    "¿Quieres que «" + selected.name() + "» se "
                            + (enable ? "inicie automáticamente al abrir el panel" : "deje de iniciarse automáticamente")
                            + "?",
                    "Inicio automático", JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) {
                appRegistry.setAutorun(selected.id(), enable);
                JOptionPane.showMessageDialog(this,
                        enable ? "La aplicación se iniciará automáticamente la próxima vez."
                                : "Se ha desactivado el inicio automático.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo cambiar el inicio automático: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void uninstallExternalApp() {
        try {
            List<AppRegistry.AppRecord> installed = appRegistry.listApps();
            if (installed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No hay apps externas instaladas.");
                return;
            }
            AppRegistry.AppRecord selected = (AppRegistry.AppRecord) JOptionPane.showInputDialog(
                    this, "Selecciona la aplicación que quieres desinstalar:", "Desinstalar app",
                    JOptionPane.WARNING_MESSAGE, null,
                    installed.toArray(AppRegistry.AppRecord[]::new), installed.get(0));
            if (selected == null) return;

            if (JOptionPane.showConfirmDialog(this,
                    "Se eliminará «" + selected.name() + "» al próximo inicio. ¿Continuar?",
                    "Confirmar desinstalación", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

            java.nio.file.Files.writeString(AppPaths.root().resolve("pending-uninstall.txt"),
                    selected.jarFilename() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            appRegistry.removeByFilename(selected.jarFilename());
            JOptionPane.showMessageDialog(this, "Desinstalación programada para el próximo inicio.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo gestionar la app: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
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

    /** Una sesión abierta: cada app tiene su propio hilo de ciclo de vida. */
    private static final class AppSession {
        private final String id;
        private final NeuroApp app;
        private final AppContext context;
        private final ExecutorService worker;
        private volatile boolean closing;
        private JComponent panel;

        private AppSession(String id, NeuroApp app, AppContext context) {
            this.id = id;
            this.app = app;
            this.context = context;
            String threadName = "app-" + id.replaceAll("[^A-Za-z0-9_-]", "_");
            this.worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, threadName);
                thread.setDaemon(true);
                return thread;
            });
        }
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
            if (apps.containsKey(appId)) SwingUtilities.invokeLater(() -> showApp(appId));
        }

        @Override public Window window() { return MainWindow.this; }

        @Override public void requestAttention() {
            SwingUtilities.invokeLater(() -> {
                Toolkit.getDefaultToolkit().beep();
                if ((getExtendedState() & Frame.ICONIFIED) != 0) setExtendedState(Frame.NORMAL);
                toFront();
                requestFocus();
            });
        }
    }
}
