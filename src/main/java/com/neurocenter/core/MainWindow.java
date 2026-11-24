package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;
import com.neurocenter.api.plugin.EventBus;
import com.neurocenter.api.plugin.ExtensionRegistry;
import com.neurocenter.api.plugin.ServiceRegistry;
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
import javax.swing.JCheckBox;
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
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.AWTException;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
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
    private static final int DELAYED_STARTUP_MS = 15_000;

    private final PropertiesStore appSettings;
    private AppRegistry appRegistry;
    private DesktopPermissionPrompts permissionPrompts;
    private final ServiceRegistry pluginServices = new ServiceRegistry();
    private final EventBus pluginEvents = new EventBus();
    private final ExtensionRegistry pluginExtensions = new ExtensionRegistry();
    private PluginManager pluginManager;
    private final Map<String, NeuroApp> apps = new LinkedHashMap<>();
    private final Set<String> externalAppIds = new HashSet<>();
    private final Map<String, String> externalJarByAppId = new HashMap<>();
    private final Map<String, URLClassLoader> externalClassLoaders = new HashMap<>();
    private final Map<String, JToggleButton> navButtons = new HashMap<>();
    private final Map<String, JPanel> navMarkers = new HashMap<>();
    private final Map<String, JPanel> navRows = new HashMap<>();
    private final Map<String, AppSession> appSessions = new LinkedHashMap<>();
    private final Map<String, javax.swing.Timer> delayedStartupTimers = new HashMap<>();
    private final JPanel openAppsList = new JPanel();
    private String activeAppId = HOME;
    private boolean shuttingDown;
    private final List<String> loadErrors = new ArrayList<>();
    private final ButtonGroup navGroup = new ButtonGroup();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private JComponent homePanel;
    private final JLabel statusLabel = new JLabel(" ");
    private JButton settingsButton;
    private TrayIcon trayIcon;

    public MainWindow(PropertiesStore appSettings) {
        super(AppInfo.NAME);
        // En Windows 10/11 FlatLaf gestiona la barra de título mediante sus
        // decoraciones nativas. No marcar el JFrame como undecorated manualmente:
        // eso elimina el marco redondeado y el comportamiento de ventana de Windows.
        this.appSettings = appSettings;

        // 1) Apps integradas en el propio .jar
        register(new AgendaApp(pluginServices), false);
        register(new PdfReaderApp(), false);

        // 2) Apps externas: solo se cargan paquetes .ncapp registrados en SQLite.
        Map<String, AppRegistry.StartupMode> startupModes = Map.of();
        try {
            appRegistry = new AppRegistry(AppPaths.root().resolve("apps.db"));
            permissionPrompts = new DesktopPermissionPrompts(this, appRegistry);
            pluginManager = new PluginManager(PluginPaths.pluginsDir(), pluginServices, pluginEvents,
                    pluginExtensions, app -> register(app, false));
            loadErrors.addAll(pluginManager.loadAll());
            AppLoader.Result externalApps = AppLoader.load(
                    AppPaths.appsDir(), appRegistry.securityProfilesByJar());
            loadErrors.addAll(externalApps.errors());
            startupModes = appRegistry.startupModesByJar();
            for (AppLoader.LoadedApp loaded : externalApps.apps()) {
                register(loaded.app(), true, loaded.jarFilename());
                externalClassLoaders.put(loaded.app().id(), loaded.classLoader());
            }
        } catch (Exception ex) {
            loadErrors.add("No se pudo abrir el registro de aplicaciones (apps.db): " + ex.getMessage());
        }

        buildUi();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { hideToTray(); }
        });
        setMinimumSize(new Dimension(960, 620));
        setSize(1240, 800);
        setLocationRelativeTo(null);
        initializeTrayIcon();
        refreshProgramIcon();
        showApp(HOME);

        // Start in the background without leaving Inicio. "Retrasado" starts
        // 15 seconds later, on the EDT, unless the app was opened manually.
        for (Map.Entry<String, String> entry : externalJarByAppId.entrySet()) {
            AppRegistry.StartupMode mode = startupModes.getOrDefault(
                    entry.getValue(), AppRegistry.StartupMode.NO);
            if (mode == AppRegistry.StartupMode.YES) {
                launchApp(entry.getKey(), false);
            } else if (mode == AppRegistry.StartupMode.DELAYED) {
                String id = entry.getKey();
                javax.swing.Timer timer = new javax.swing.Timer(DELAYED_STARTUP_MS, e -> {
                    delayedStartupTimers.remove(id);
                    if (!shuttingDown && apps.containsKey(id)) launchApp(id, false);
                });
                timer.setRepeats(false);
                delayedStartupTimers.put(id, timer);
                timer.start();
            }
        }

        if (!loadErrors.isEmpty()) SwingUtilities.invokeLater(this::showLoadErrors);
    }

    // ------------------------------------------------------------------ registro y arranque

    private void register(NeuroApp app, boolean external) {
        register(app, external, null);
    }

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

    /**
     * Añade el icono permanente del programa al área de notificación de Windows.
     * El menú contextual permite recuperar la ventana o terminar el proceso.
     */
    private void initializeTrayIcon() {
        if (!SystemTray.isSupported()) {
            System.err.println("El sistema no admite iconos de bandeja.");
            return;
        }

        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Abrir");
        openItem.addActionListener(e -> restoreFromTray());
        menu.add(openItem);
        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Salir");
        exitItem.addActionListener(e -> shutdown());
        menu.add(exitItem);

        TrayIcon candidate = new TrayIcon(createTrayImage(), AppInfo.NAME, menu);
        candidate.setImageAutoSize(true);
        candidate.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2) {
                    restoreFromTray();
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(candidate);
            trayIcon = candidate;
        } catch (AWTException | RuntimeException ex) {
            trayIcon = null;
            System.err.println("No se pudo añadir el icono al área de notificación: " + ex.getMessage());
        }
    }

    /**
     * Actualiza el icono de ventana, bandeja y accesos directos de Windows.
     * La búsqueda de .lnk se hace fuera del EDT y solo modifica los que apuntan
     * exactamente al ejecutable de esta instalación.
     */
    private void refreshProgramIcon() {
        setIconImage(createTrayImage());
        if (trayIcon != null) {
            trayIcon.setImage(createTrayImage());
            trayIcon.setToolTip(AppInfo.NAME);
        }

        Path customIcon = ThemeManager.centerIconPath();
        Thread updater = new Thread(() -> {
            try {
                int updated = WindowsShortcutManager.refresh(customIcon);
                if (updated > 0) {
                    System.out.println("Icono de NeuroCenter actualizado en " + updated + " accesos directos.");
                }
            } catch (Exception ex) {
                System.err.println("No se pudieron actualizar los accesos directos de NeuroCenter: "
                        + ex.getMessage());
            }
        }, "windows-shortcut-icon-refresh");
        updater.setDaemon(true);
        updater.start();
    }

    /** Crea el icono de la bandeja desde el tema o usa el icono integrado de reserva. */
    private Image createTrayImage() {
        Image themed = ThemeManager.centerIconImage(32);
        if (themed != null) return themed;

        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(55, 72, 112));
            graphics.fillRoundRect(1, 1, 30, 30, 9, 9);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
            String mark = "N";
            var metrics = graphics.getFontMetrics();
            int x = (32 - metrics.stringWidth(mark)) / 2;
            int y = (32 - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(mark, x, y);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Oculta la ventana al pulsar la X; no altera el botón de minimizar. */
    private void hideToTray() {
        if (trayIcon == null) {
            setExtendedState(getExtendedState() | Frame.ICONIFIED);
            JOptionPane.showMessageDialog(this,
                    "No se ha podido crear el icono del área de notificación. "
                            + "La ventana se ha minimizado a la barra de tareas.",
                    AppInfo.NAME, JOptionPane.WARNING_MESSAGE);
            return;
        }
        setVisible(false);
    }

    private void restoreFromTray() {
        if (shuttingDown) return;
        if (!isVisible()) setVisible(true);
        setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
        toFront();
        requestFocus();
    }

    /**
     * Muestra esta ventana cuando el usuario vuelve a ejecutar el acceso directo.
     * Puede recibirse desde el hilo de IPC; toda interacción Swing se traslada
     * al hilo de eventos.
     */
    public void activateFromAnotherLaunch() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::activateFromAnotherLaunch);
            return;
        }
        restoreFromTray();
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        delayedStartupTimers.values().forEach(javax.swing.Timer::stop);
        delayedStartupTimers.clear();

        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }

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

        if (pluginManager != null) pluginManager.close();
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
            for (URLClassLoader loader : externalClassLoaders.values()) {
                try { loader.close(); }
                catch (IOException ignored) { /* Shutdown proceeds even on locked files. */ }
            }
            externalClassLoaders.clear();
            System.exit(0);
        }, "app-shutdown");
        terminator.setDaemon(false);
        terminator.start();
    }

    // ------------------------------------------------------------------ interfaz

    private void buildUi() {
        JPanel rootPanel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                ThemeManager.paintBackground((Graphics2D) graphics, getWidth(), getHeight());
            }
        };
        rootPanel.setOpaque(true);
        setContentPane(rootPanel);

        rootPanel.add(buildSidebar(), BorderLayout.WEST);

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
        topBar.setOpaque(false);
        topBar.add(settingsButton);
        rootPanel.add(topBar, BorderLayout.NORTH);

        content.setOpaque(false);
        homePanel = buildHome();
        content.add(homePanel, HOME);
        rootPanel.add(content, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setOpaque(false);
        statusBar.setBorder(new EmptyBorder(5, 14, 5, 14));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(statusBar, BorderLayout.CENTER);
        rootPanel.add(south, BorderLayout.SOUTH);
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel();
        side.setOpaque(false);
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
        wrapper.setOpaque(false);
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

    /** Refreshes cards without destroying active app sessions or rebuilding the window. */
    private void refreshHomeCards() {
        if (homePanel != null) content.remove(homePanel);
        homePanel = buildHome();
        content.add(homePanel, HOME);
        content.revalidate();
        content.repaint();
        cards.show(content, HOME.equals(activeAppId) ? HOME : activeAppId);
    }

    private JComponent buildHome() {
        JPanel root = new JPanel(new BorderLayout(0, 24));
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(36, 44, 24, 44));

        JLabel heading = new JLabel("<html><div style='font-size:22pt'><b>Tus apps</b></div>"
                + "<div style='margin-top:6px'>Elige uno para empezar.</div></html>");
        root.add(heading, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 2, 16, 16));
        grid.setOpaque(false);
        for (NeuroApp p : apps.values()) grid.add(buildCard(p));
        JPanel gridTop = new JPanel(new BorderLayout());
        gridTop.setOpaque(false);
        gridTop.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(gridTop);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
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
        javax.swing.Timer pending = delayedStartupTimers.remove(id);
        if (pending != null) pending.stop();
        if (shuttingDown) return;
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

        pluginEvents.publish(new PluginManager.ApplicationStartedEvent(session.id, session.app.name()));
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
        closeApp(session, null);
    }

    /** Optional callback runs on Swing EDT, after the old app's stop() finishes. */
    private void closeApp(AppSession session, Runnable afterClosed) {
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
                if (permissionPrompts != null && externalAppIds.contains(session.id)) {
                    try {
                        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                            permissionPrompts.close(session.app, externalJarByAppId.get(session.id));
                            return null;
                        });
                    } catch (RuntimeException ex) {
                        ex.printStackTrace();
                    }
                }
                session.worker.shutdown();
                SwingUtilities.invokeLater(() -> {
                    pluginEvents.publish(new PluginManager.ApplicationStoppedEvent(session.id, session.app.name()));
                    if (appSessions.get(session.id) == session) {
                        appSessions.remove(session.id);
                    }
                    if (!shuttingDown
                            && statusLabel.getText().equals("Cerrando " + session.app.name() + "…")) {
                        statusLabel.setText(" ");
                    }
                    if (!shuttingDown && afterClosed != null) afterClosed.run();
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
        String[] labels = {"Cambiar tema", "Gestionar temas", "Gestionar apps", "Gestionar plugins"};
        Runnable[] actions = {
                this::chooseTheme,
                this::manageThemes,
                this::manageApps,
                this::managePlugins
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

    private void managePlugins() {
        if (pluginManager == null) {
            JOptionPane.showMessageDialog(this, "El gestor de plugins no se pudo inicializar.", "Gestionar plugins", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String[] actions = {"Instalar plugin", "Activar o desactivar", "Desinstalar plugin", "Cerrar"};
        int action = JOptionPane.showOptionDialog(this,
                "Los plugins tienen acceso completo al sistema. Instala solo paquetes de confianza.\nLos cambios de estado requieren reiniciar NeuroCenter.",
                "Gestionar plugins", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, actions, actions[0]);
        try {
            if (action == 0) {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                chooser.setDialogTitle("Instalar plugin de NeuroCenter");
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Plugins NeuroCenter (*.ncp)", "ncp"));
                if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    pluginManager.install(chooser.getSelectedFile().toPath());
                    JOptionPane.showMessageDialog(this, "Plugin instalado. Reinicia NeuroCenter para cargarlo.");
                }
            } else if (action == 1 || action == 2) {
                List<String> packages = pluginManager.installedPackageNames();
                if (packages.isEmpty()) { JOptionPane.showMessageDialog(this, "No hay plugins instalados."); return; }
                String selected = (String) JOptionPane.showInputDialog(this, "Selecciona un paquete:",
                        "Gestionar plugins", JOptionPane.PLAIN_MESSAGE, null, packages.toArray(), packages.get(0));
                if (selected == null) return;
                if (action == 1) {
                    boolean enabled = !pluginManager.isEnabled(selected);
                    pluginManager.setEnabled(selected, enabled);
                    JOptionPane.showMessageDialog(this, (enabled ? "Se activará" : "Se desactivará")
                            + " «" + selected + "» al reiniciar NeuroCenter.");
                } else {
                    pluginManager.uninstall(selected);
                    JOptionPane.showMessageDialog(this, "Plugin desinstalado.");
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Error al gestionar plugins", JOptionPane.ERROR_MESSAGE);
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
                try {
                    // Aplicar primero: un tema incompatible no debe quedar guardado
                    // como preferencia ni provocar un fallo al siguiente arranque.
                    ThemeManager.apply(themeId);
                    appSettings.put("theme", themeId);
                    refreshThemeIcons();
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            "Tema no compatible", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void refreshThemeIcons() {
        refreshProgramIcon();
        repaint();
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

        String[] options = {"Instalar o actualizar (.ncapp)", "Desinstalar app", "Configurar inicio automático",
                "Configurar permisos"};
        int choice = JOptionPane.showOptionDialog(this,
                "Formato único de aplicaciones: .ncapp (escritorio y Android). "
                        + "Cada paquete declara las plataformas que admite. "
                        + "Los permisos se limitan a los declarados en su manifiesto.",
                "Gestor de apps", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);

        if (choice == 0) {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setDialogTitle("Instalar o actualizar una aplicación (.ncapp)");
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Aplicaciones NeuroCenter (*.ncapp)", "ncapp"));
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                installNcapp(chooser.getSelectedFile().toPath());
            }
        } else if (choice == 1) {
            uninstallExternalApp();
        } else if (choice == 2) {
            configureAppAutorun();
        } else if (choice == 3) {
            configureAppPermissions();
        }
    }

    /**
     * One-click install or update by manifest app ID, independently of the
     * source filename. No NeuroCenter restart is needed.
     */
    public void installNcapp(Path source) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> installNcapp(source));
            return;
        }
        activateFromAnotherLaunch();
        if (appRegistry == null) {
            JOptionPane.showMessageDialog(this, "El registro de aplicaciones no está disponible.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Path staged = null;
        AppLoader.LoadedApp prepared = null;
        try {
            AppRegistry.PackageInfo metadata = AppRegistry.inspectPackage(source);
            AppRegistry.AppRecord current = appRegistry.listApps().stream()
                    .filter(row -> row.id().equals(metadata.id()))
                    .findFirst().orElse(null);
            if (current != null && !externalAppIds.contains(metadata.id())
                    && apps.containsKey(metadata.id())) {
                throw new IOException("Hay una aplicación integrada con ese ID; no se puede sustituir.");
            }
            if (current == null && apps.containsKey(metadata.id())) {
                throw new IOException("Ya existe una aplicación o plugin con el ID " + metadata.id());
            }
            AppSession existing = appSessions.get(metadata.id());
            if (existing != null && existing.closing)
                throw new IOException("La aplicación está cerrándose. Inténtalo de nuevo cuando termine.");

            String operation = current == null ? "Instalar" : "Actualizar";
            StringBuilder confirmation = new StringBuilder();
            confirmation.append(operation).append(" «").append(metadata.name()).append("»");
            confirmation.append("\nVersión del paquete: ").append(metadata.version());
            if (current != null) {
                confirmation.append("\nVersión instalada: ").append(current.version());
                Set<String> previous = appRegistry.declaredPermissions(current.id());
                Set<String> added = new HashSet<>(metadata.declaredPermissions());
                added.removeAll(previous);
                if (!added.isEmpty()) {
                    confirmation.append("\n\nPermisos nuevos declarados (NO se concederán automáticamente):");
                    for (String permission : added)
                        confirmation.append("\n  • ").append(permission);
                }
                confirmation.append("\n\nSe conservarán los datos, el inicio automático");
                confirmation.append("\ny las decisiones de permisos que sigan declarados.");
                if (existing != null && hasUnsavedChanges(existing.app)) {
                    confirmation.append("\n\nATENCIÓN: esta app tiene cambios sin guardar.");
                    confirmation.append("\nSe cerrará la app antes de actualizarla.");
                }
                confirmation.append("\n\nEl paquete debe proceder de una fuente de confianza:");
                confirmation.append("\nNeuroCenter no verifica firmas criptográficas .ncapp.");
            } else {
                confirmation.append("\n\nSe instalará y podrá abrirse ahora, sin reiniciar NeuroCenter.");
            }
            int reply = JOptionPane.showConfirmDialog(this, confirmation.toString(),
                    operation + " aplicación NeuroCenter", JOptionPane.YES_NO_OPTION,
                    current != null ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
            if (reply != JOptionPane.YES_OPTION) return;

            // Unique immutable archive avoids overwriting an open ZIP on Windows.
            String stagedName = metadata.id() + "-" + java.util.UUID.randomUUID() + ".ncapp";
            staged = AppPaths.appsDir().resolve(stagedName);
            Files.copy(source, staged);
            AppRegistry.PackageInfo copied = AppRegistry.inspectPackage(staged);
            if (!copied.id().equals(metadata.id())
                    || !copied.declaredPermissions().equals(metadata.declaredPermissions()))
                throw new IOException("El contenido del .ncapp cambió durante la copia.");

            Set<String> previousGrants = current == null
                    ? Set.of() : appRegistry.grantedPermissions(current.id());
            Set<String> grants = new HashSet<>(previousGrants);
            grants.retainAll(metadata.declaredPermissions());
            AppRegistry.AppSecurityProfile profile = new AppRegistry.AppSecurityProfile(
                    metadata.id(), grants, metadata.declaredPermissions());
            AppLoader.Result validation = AppLoader.load(AppPaths.appsDir(), Map.of(stagedName, profile));
            if (validation.apps().size() != 1 || !validation.errors().isEmpty())
                throw new IOException("La nueva aplicación no se pudo cargar: "
                        + String.join(" | ", validation.errors()));
            prepared = validation.apps().get(0);

            if (current == null) {
                appRegistry.registerJar(staged);
                register(prepared.app(), true, stagedName);
                externalClassLoaders.put(metadata.id(), prepared.classLoader());
                refreshHomeCards();
                JOptionPane.showMessageDialog(this,
                        "«" + metadata.name() + "» instalada correctamente. Ya puedes abrirla.",
                        "Instalación completada", JOptionPane.INFORMATION_MESSAGE);
            } else {
                final Path preparedArchive = staged;
                final AppLoader.LoadedApp ready = prepared;
                final boolean reopen = existing != null;
                final boolean showUpdated = reopen && metadata.id().equals(activeAppId);
                Runnable finishUpdate = () -> applyNcappUpdate(current, preparedArchive, ready, reopen, showUpdated);
                if (existing != null) closeApp(existing, finishUpdate);
                else finishUpdate.run();
            }
            // Ownership transferred to app registry and classloader map.
            staged = null;
            prepared = null;
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "No se pudo instalar o actualizar la aplicación:\n"
                    + error.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (prepared != null) closePreparedPackage(prepared, staged);
            else if (staged != null) {
                NeuroCenterSecurity.unregisterExternalApp(staged);
                try { Files.deleteIfExists(staged); }
                catch (IOException ignored) { queueOldPackageDeletion(staged); }
            }
        }
    }

    /**
     * Swaps the running application only AFTER its worker has called stop().
     * Existing app settings are under the stable ID in NeuroCenter's data dir.
     */
    private void applyNcappUpdate(AppRegistry.AppRecord old, Path staged,
                                  AppLoader.LoadedApp prepared,
                                  boolean reopen, boolean activate) {
        try {
            appRegistry.updateJar(old.id(), staged);
        } catch (Exception failed) {
            closePreparedPackage(prepared, staged);
            JOptionPane.showMessageDialog(this, "La actualización no se ha aplicado:\n"
                    + failed.getMessage() + "\nLa versión anterior permanece instalada.",
                    "Error al actualizar", JOptionPane.ERROR_MESSAGE);
            return;
        }

        javax.swing.Timer timer = delayedStartupTimers.remove(old.id());
        if (timer != null) timer.stop();
        URLClassLoader previousLoader = externalClassLoaders.put(old.id(), prepared.classLoader());
        apps.put(old.id(), prepared.app());
        externalJarByAppId.put(old.id(), prepared.jarFilename());
        refreshHomeCards();
        if (previousLoader != null) {
            try { previousLoader.close(); }
            catch (IOException ignored) { /* The obsolete file will be cleaned later. */ }
        }
        Path previousArchive = AppPaths.appsDir().resolve(old.jarFilename());
        NeuroCenterSecurity.unregisterExternalApp(previousArchive);
        try {
            Files.deleteIfExists(previousArchive);
        } catch (IOException locked) {
            queueOldPackageDeletion(previousArchive);
        }

        if (reopen) launchApp(old.id(), activate);
        JOptionPane.showMessageDialog(this, "«" + prepared.app().name()
                        + "» actualizada a " + safeVersion(prepared.app())
                        + ".\nNo ha sido necesario reiniciar NeuroCenter.",
                "Actualización completada", JOptionPane.INFORMATION_MESSAGE);
    }

    private void closePreparedPackage(AppLoader.LoadedApp prepared, Path staged) {
        try { prepared.close(); }
        catch (IOException ignored) { }
        if (staged != null) {
            NeuroCenterSecurity.unregisterExternalApp(staged);
            try { Files.deleteIfExists(staged); }
            catch (IOException ignored) { queueOldPackageDeletion(staged); }
        }
    }

    private static boolean queueOldPackageDeletion(Path archive) {
        try {
            Files.writeString(AppPaths.root().resolve("pending-uninstall.txt"),
                    archive.getFileName() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (IOException failure) {
            System.err.println("No se pudo programar la limpieza del paquete antiguo: " + failure);
            return false;
        }
    }

    private void configureAppAutorun() {
        try {
            List<AppRegistry.AppRecord> installed = appRegistry.listApps();
            if (installed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No hay aplicaciones externas instaladas.");
                return;
            }

            JPanel rows = new JPanel();
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setBorder(new EmptyBorder(8, 12, 8, 12));

            JLabel heading = new JLabel("Inicio automático de las aplicaciones instaladas");
            heading.setFont(heading.getFont().deriveFont(Font.BOLD));
            heading.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(heading);
            rows.add(Box.createVerticalStrut(10));

            Map<String, javax.swing.JComboBox<String>> selectors = new LinkedHashMap<>();
            for (AppRegistry.AppRecord installedApp : installed) {
                JPanel line = new JPanel(new BorderLayout(16, 0));
                line.setBorder(new EmptyBorder(5, 0, 5, 0));
                JLabel label = new JLabel(installedApp.name());
                label.setToolTipText(installedApp.id());
                line.add(label, BorderLayout.CENTER);

                javax.swing.JComboBox<String> choice =
                        new javax.swing.JComboBox<>(new String[]{"No", "Sí", "Retrasado (15 segundos)"});
                choice.setSelectedIndex(switch (installedApp.startupMode()) {
                    case NO -> 0;
                    case YES -> 1;
                    case DELAYED -> 2;
                });
                choice.setPreferredSize(new Dimension(190, choice.getPreferredSize().height));
                line.add(choice, BorderLayout.EAST);
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                rows.add(line);
                selectors.put(installedApp.id(), choice);
            }
            rows.add(Box.createVerticalStrut(12));
            JLabel help = new JLabel("Retrasado: inicia tras 15 segundos, sin abrir su pantalla.");
            help.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(help);
            JLabel when = new JLabel("Los cambios se aplicarán al volver a iniciar NeuroCenter.");
            when.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(when);

            JScrollPane scroll = new JScrollPane(rows);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setPreferredSize(new Dimension(550, Math.min(410, 95 + installed.size() * 43)));
            int answer = JOptionPane.showConfirmDialog(this, scroll,
                    "Configurar inicio automático", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;

            Map<String, AppRegistry.StartupMode> updated = new LinkedHashMap<>();
            for (Map.Entry<String, javax.swing.JComboBox<String>> selection : selectors.entrySet()) {
                updated.put(selection.getKey(), switch (selection.getValue().getSelectedIndex()) {
                    case 1 -> AppRegistry.StartupMode.YES;
                    case 2 -> AppRegistry.StartupMode.DELAYED;
                    default -> AppRegistry.StartupMode.NO;
                });
            }
            appRegistry.setStartupModes(updated);
            JOptionPane.showMessageDialog(this,
                    "Preferencias de inicio automático guardadas. Se aplicarán al próximo inicio.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo configurar el inicio automático: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void configureAppPermissions() {
        try {
            List<AppRegistry.AppRecord> installed = appRegistry.listApps();
            if (installed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No hay apps externas instaladas.");
                return;
            }

            AppRegistry.AppRecord selected = (AppRegistry.AppRecord) JOptionPane.showInputDialog(
                    this, "Selecciona la aplicación cuyos permisos quieres configurar:",
                    "Permisos de aplicaciones", JOptionPane.QUESTION_MESSAGE, null,
                    installed.toArray(AppRegistry.AppRecord[]::new), installed.get(0));
            if (selected == null) return;

            Set<String> declared = appRegistry.declaredPermissions(selected.id());
            JPanel options = new JPanel();
            options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
            options.add(new JLabel("Permisos para «" + selected.name() + "»:"));
            options.add(Box.createVerticalStrut(8));

            Map<String, javax.swing.JComboBox<String>> controls = new LinkedHashMap<>();
            for (NeuroCenterSecurity.AppPermission permission : NeuroCenterSecurity.supportedPermissions()) {
                if (!declared.contains(permission.id())) continue;
                Boolean decision = appRegistry.storedDecision(selected.id(), permission.id());
                String[] choices = {"Preguntar", "Permitir siempre", "Denegar siempre"};
                javax.swing.JComboBox<String> choice = new javax.swing.JComboBox<>(choices);
                choice.setSelectedIndex(decision == null ? 0 : decision ? 1 : 2);
                controls.put(permission.id(), choice);
                JPanel line = new JPanel(new BorderLayout(8, 0));
                line.add(new JLabel(permission.name()), BorderLayout.CENTER);
                line.add(choice, BorderLayout.EAST);
                options.add(line);
                JLabel description = new JLabel("  " + permission.description());
                description.setBorder(new EmptyBorder(0, 20, 7, 0));
                description.setAlignmentX(Component.LEFT_ALIGNMENT);
                options.add(description);
            }
            if (controls.isEmpty()) options.add(new JLabel("Esta aplicación no declara permisos."));

            int answer = JOptionPane.showConfirmDialog(this, options,
                    "Permisos de «" + selected.name() + "»",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;

            for (Map.Entry<String, javax.swing.JComboBox<String>> item : controls.entrySet()) {
                int option = item.getValue().getSelectedIndex();
                appRegistry.setDecision(selected.id(), item.getKey(),
                        option == 0 ? null : option == 1);
            }
            if (apps.containsKey(selected.id()) && permissionPrompts != null)
                permissionPrompts.refreshPolicy(apps.get(selected.id()), selected.jarFilename());
            JOptionPane.showMessageDialog(this, "Permisos guardados. Se aplican inmediatamente.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "No se pudieron guardar los permisos: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Removes a .ncapp from this running NeuroCenter, never other apps. */
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
            if (!externalAppIds.contains(selected.id()))
                throw new IllegalStateException(
                        "La aplicación no está cargada. Corrige su instalación antes de eliminarla.");

            AppSession running = appSessions.get(selected.id());
            if (running != null && running.closing) {
                JOptionPane.showMessageDialog(this,
                        "Esta aplicación todavía está cerrándose. Inténtalo de nuevo cuando termine.",
                        "Desinstalar app", JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean unsaved = running != null && hasUnsavedChanges(running.app);
            String message = "¿Desinstalar «" + selected.name() + "» ahora?\n\n"
                    + "Se cerrará únicamente esta aplicación si está abierta y desaparecerá "
                    + "de Inicio inmediatamente.\n"
                    + "NeuroCenter y las demás apps seguirán funcionando.\n"
                    + "Se revocarán los permisos de la app; su carpeta de datos se conservará."
                    + (unsaved ? "\n\nATENCIÓN: la aplicación declara cambios sin guardar." : "");
            if (JOptionPane.showConfirmDialog(this, message,
                    "Confirmar desinstalación", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;

            if (running != null) {
                statusLabel.setText("Desinstalando " + selected.name() + "…");
                closeApp(running, () -> finishUninstallExternalApp(selected));
            } else {
                finishUninstallExternalApp(selected);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo gestionar la app: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Runs on Swing EDT after stop() and session cleanup have completed. */
    private void finishUninstallExternalApp(AppRegistry.AppRecord selected) {
        String appId = selected.id();
        Path archive = AppPaths.appsDir().resolve(selected.jarFilename());
        try {
            // Do NOT unload the app until SQLite has forgotten the package.
            // If the transaction fails, the old app remains available to reopen.
            appRegistry.removeByFilename(selected.jarFilename());
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this, "No se pudo completar la desinstalación:\n"
                    + failure.getMessage() + "\nLa aplicación sigue instalada.",
                    "Error de desinstalación", JOptionPane.ERROR_MESSAGE);
            return;
        }

        javax.swing.Timer timer = delayedStartupTimers.remove(appId);
        if (timer != null) timer.stop();
        URLClassLoader loader = externalClassLoaders.remove(appId);
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException closeFailure) {
                System.err.println("No se pudo liberar el archivo de la app: " + closeFailure);
            }
        }
        NeuroCenterSecurity.unregisterExternalApp(archive);
        apps.remove(appId);
        externalAppIds.remove(appId);
        externalJarByAppId.remove(appId);
        refreshHomeCards();

        boolean pendingCleanup = false;
        boolean cleanupScheduled = false;
        try {
            Files.deleteIfExists(archive);
        } catch (IOException locked) {
            pendingCleanup = true;
            cleanupScheduled = queueOldPackageDeletion(archive);
        }

        if (!pendingCleanup) {
            JOptionPane.showMessageDialog(this,
                    "«" + selected.name() + "» se ha desinstalado.\n"
                            + "No necesitas reiniciar NeuroCenter.",
                    "Desinstalación completada", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (!cleanupScheduled) {
            JOptionPane.showMessageDialog(this,
                    "La aplicación ya se ha retirado de NeuroCenter, pero Windows mantiene "
                            + "bloqueado su archivo:\n" + archive
                            + "\n\nNo se pudo programar su borrado automático. "
                            + "Podrás eliminarlo manualmente después de cerrar NeuroCenter.",
                    "Archivo pendiente de eliminar", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Object[] options = {"Reiniciar NeuroCenter", "Más tarde"};
        int answer = JOptionPane.showOptionDialog(this,
                "«" + selected.name() + "» ya está desinstalada y no aparece en Inicio.\n"
                        + "Windows mantiene bloqueado el archivo antiguo.\n"
                        + "Está programado para eliminarse al próximo inicio de NeuroCenter.\n\n"
                        + "¿Quieres reiniciar NeuroCenter ahora para completar la limpieza?",
                "Limpieza pendiente", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (answer == 0) restartNeuroCenter();
    }

    /**
     * Restart only the packaged Windows executable. A detached PowerShell
     * process waits until the single-instance lock has been released by the
     * main process, then starts the same installed launcher.
     */
    private void restartNeuroCenter() {
        String launcher = System.getProperty("jpackage.app-path", "");
        if (launcher.isBlank()) {
            launcher = ProcessHandle.current().info().command().orElse("");
            if (!launcher.toLowerCase(java.util.Locale.ROOT)
                    .endsWith("center of neurodivergence.exe")) launcher = "";
        }
        if (launcher.isBlank() || !Files.isRegularFile(Path.of(launcher))) {
            JOptionPane.showMessageDialog(this,
                    "No se ha podido identificar el ejecutable instalado.\n"
                            + "Cierra NeuroCenter y vuelve a abrirlo manualmente para terminar la limpieza.",
                    "Reinicio no disponible", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // A single-quoted PowerShell literal handles spaces and quotes in paths
        // without treating the install directory as executable script.
        String safeLauncher = launcher.replace("'", "''");
        long pid = ProcessHandle.current().pid();
        String command = "Wait-Process -Id " + pid
                + " -ErrorAction SilentlyContinue; "
                + "Start-Process -FilePath '" + safeLauncher + "'";
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", command).start();
            shutdown();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo preparar el reinicio automático:\n" + ex.getMessage()
                            + "\n\nPuedes cerrar y abrir NeuroCenter manualmente.",
                    "Error al reiniciar", JOptionPane.WARNING_MESSAGE);
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

        @Override public boolean requestPermission(String id) {
            if (!externalAppIds.contains(app.id())) return true;
            return AccessController.doPrivileged((PrivilegedAction<Boolean>) () ->
                    permissionPrompts.request(app, externalJarByAppId.get(app.id()), id));
        }

        @Override public boolean hasPermission(String id) {
            if (!externalAppIds.contains(app.id())) return true;
            return AccessController.doPrivileged((PrivilegedAction<Boolean>) () ->
                    permissionPrompts.has(app, id));
        }

        @Override public Path dataDir() { return AppPaths.appDataDir(app.id()); }
        @Override public ServiceRegistry services() { return pluginServices; }
        @Override public EventBus events() { return pluginEvents; }
        @Override public ExtensionRegistry extensions() { return pluginExtensions; }

        private synchronized PropertiesStore store() {
            if (store == null) store = new PropertiesStore(dataDir().resolve("settings.properties"));
            return store;
        }

        @Override public String getSetting(String key, String defaultValue) { return store().get(key, defaultValue); }

        @Override public void putSetting(String key, String value) { store().put(key, value); }

        /**
         * Los apps integrados y plugins son código de confianza del propio NeuroCenter.
         * Los paquetes .ncapp externos necesitan el permiso explícito credentials.access.
         */
        private void requireCredentialPermission() {
            if (externalAppIds.contains(app.id()) && !hasPermission("credentials.access"))
                throw new SecurityException("La app no tiene el permiso credentials.access.");
        }

        @Override public void saveSecret(String key, char[] secret) {
            requireCredentialPermission();
            WindowsCredentialVault.save(app.id(), key, secret);
        }

        @Override public char[] loadSecret(String key) {
            requireCredentialPermission();
            return WindowsCredentialVault.load(app.id(), key);
        }

        @Override public void deleteSecret(String key) {
            requireCredentialPermission();
            WindowsCredentialVault.delete(app.id(), key);
        }

        @Override public void setStatus(String message) {
            String text = message == null || message.isBlank() ? " " : message;
            if (SwingUtilities.isEventDispatchThread()) statusLabel.setText(text);
            else SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }

        @Override public void openApp(String appId) {
            if (apps.containsKey(appId)) SwingUtilities.invokeLater(() -> showApp(appId));
        }

        @Override public Window window() { return MainWindow.this; }

        @Override public void openExternalUrl(URI uri) {
            if (uri == null || uri.getHost() == null
                    || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Solo se pueden abrir enlaces HTTP o HTTPS.");
            }

            // La integración con el navegador pertenece al host de confianza.
            // No se concede a la app permiso general para ejecutar archivos.
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                try {
                    if (!Desktop.isDesktopSupported()) {
                        throw new IllegalStateException("Este sistema no permite abrir el navegador desde NeuroCenter.");
                    }
                    Desktop desktop = Desktop.getDesktop();
                    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                        throw new IllegalStateException("No hay un navegador predeterminado disponible.");
                    }
                    desktop.browse(uri);
                    return null;
                } catch (IOException error) {
                    throw new UncheckedIOException("No se pudo abrir el enlace.", error);
                }
            });
        }

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
