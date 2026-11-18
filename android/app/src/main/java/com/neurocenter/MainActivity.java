package com.neurocenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.content.res.ColorStateList;

import com.neurocenter.api.NeuroMobileApp;
import com.neurocenter.api.NeuroMobileContext;
import com.neurocenter.api.NeuroMobilePermissions;
import com.neurocenter.apps.agenda.AgendaMobileApp;
import com.neurocenter.core.AndroidCredentialVault;
import com.neurocenter.core.AppInfo;
import com.neurocenter.core.AppLoader;
import com.neurocenter.core.AppPaths;
import com.neurocenter.core.AppRegistry;
import com.neurocenter.core.PropertiesStore;
import com.neurocenter.core.ThemeArchive;
import com.neurocenter.core.ThemeManager;
import com.neurocenter.core.ThemeManager.Palette;
import com.neurocenter.ui.BadgeDrawable;
import com.neurocenter.ui.Ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Center of Neurodivergence para Android: el panel.
 *
 * Equivale a MainWindow de escritorio, adaptado a móvil: pantalla de Inicio con las apps,
 * menú lateral con las apps abiertas, ajustes generales (temas y apps externas) y una barra
 * de estado. Agenda es una app integrada; las demás se instalan como APK/JAR con classes.dex.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "NeuroCenter";
    private static final String HOME = "__home__";
    private static final String AGENDA_ID = "agenda";

    // El host usa 9100–9199; las apps eligen sus propios códigos fuera de ese rango.
    private static final int REQUEST_INSTALL_APP = 9101;
    private static final int REQUEST_INSTALL_THEME = 9102;

    private static final ViewGroup.LayoutParams MATCH = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

    private PropertiesStore settings;
    private AppRegistry registry;
    private final Map<String, NeuroMobileApp> apps = new LinkedHashMap<>();
    private final Set<String> externalAppIds = new HashSet<>();
    private final Map<String, String> externalFileByAppId = new HashMap<>();
    private final Set<String> autorunFiles = new HashSet<>();
    private final Map<String, AppSession> sessions = new LinkedHashMap<>();
    private final List<String> loadErrors = new ArrayList<>();
    private final Map<Integer, PendingAppRequest> pendingAppRequests = new HashMap<>();
    private int nextAppRequestCode = 0x7000;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "neurocenter-io");
        thread.setDaemon(true);
        return thread;
    });

    private String activeId = HOME;
    private boolean activityResumed;
    private boolean drawerOpen;
    private int drawerWidth;
    private String statusText = "";
    /** OnBackInvokedCallback (API 33+); se guarda como Object para no cargar la clase en versiones antiguas. */
    private Object backCallback;

    private FrameLayout root;
    private FrameLayout contentFrame;
    private LinearLayout drawer;
    private View scrim;
    private TextView titleView;
    private TextView statusView;
    private View homeView;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppPaths.ensureDirectories(this);
        ThemeManager.init(this);
        settings = new PropertiesStore(AppPaths.root(this).resolve("settings.properties"));
        applyStoredTheme();

        // 1) Apps integradas
        String error = registerApp(new AgendaMobileApp(), false, null);
        if (error != null) loadErrors.add(error);

        // 2) Apps externas: solo se cargan los paquetes inscritos en el registro SQLite
        loadExternalApps();

        applyWindowColors(ThemeManager.palette());
        buildUi();

        // Las apps con inicio automático arrancan en segundo plano; Inicio sigue siendo la pantalla activa.
        for (Map.Entry<String, String> entry : new ArrayList<>(externalFileByAppId.entrySet())) {
            if (autorunFiles.contains(entry.getValue())) launchApp(entry.getKey(), false);
        }

        handleIntent(getIntent());
        if (!loadErrors.isEmpty()) root.post(this::showLoadErrors);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Un accesorio USB (el PC en modo AOA) abre la Agenda para que se encargue de la conexión. */
    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())
                && apps.containsKey(AGENDA_ID)) {
            showApp(AGENDA_ID);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        dispatchResume(sessions.get(activeId));
    }

    @Override
    protected void onPause() {
        dispatchPause(sessions.get(activeId));
        activityResumed = false;
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Las vistas antiguas cuelgan del contenedor anterior. Reconstruimos cada sesión
        // para no dejar el contenido activo dentro de una jerarquía ya desconectada.
        AppSession active = sessions.get(activeId);
        dispatchPause(active);
        for (AppSession session : new ArrayList<>(sessions.values())) {
            if (session.ready && !session.closing) rebuildSessionView(session);
            else if (!session.closing) session.view = loadingView(session.app.name());
        }
        homeView = buildHome();
        buildUi();
        dispatchResume(active);
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
        for (AppSession session : new ArrayList<>(sessions.values())) stopSession(session);
        sessions.clear();
        pendingAppRequests.clear();
        ioExecutor.shutdown();
        if (registry != null) registry.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ registro de apps

    private void applyStoredTheme() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        String themeId = settings.get("theme", night ? "dark" : "light");
        try {
            ThemeManager.apply(themeId);
        } catch (IllegalArgumentException ex) {
            // Puede ocurrir si la preferencia apunta a un tema solo de escritorio.
            settings.put("theme", "light");
            ThemeManager.apply("light");
            loadErrors.add(ex.getMessage() + " Se ha restaurado el tema Claro.");
        }
    }

    private void loadExternalApps() {
        try {
            registry = new AppRegistry(AppPaths.appsDatabase(this));
            AppLoader.Result result = AppLoader.load(this, registry.installedFileNames());
            loadErrors.addAll(result.errors);
            autorunFiles.addAll(registry.autorunFileNames());
            for (AppLoader.LoadedApp loaded : result.apps) {
                String error = registerApp(loaded.app, true, loaded.fileName);
                if (error != null) loadErrors.add(error);
            }
        } catch (Exception ex) {
            loadErrors.add("No se pudo abrir el registro de aplicaciones (apps.db): " + ex.getMessage());
        }
    }

    /** Devuelve null si la app se registró, o el motivo del rechazo. */
    private String registerApp(NeuroMobileApp app, boolean external, String fileName) {
        try {
            String id = app.id();
            String name = app.name();
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("el id está vacío");
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("el nombre está vacío");
            if (apps.containsKey(id)) return "Hay dos apps con el id «" + id + "»: se ignora «" + name + "».";
            apps.put(id, app);
            if (external) {
                externalAppIds.add(id);
                externalFileByAppId.put(id, fileName);
            }
            return null;
        } catch (Throwable t) {
            return app.getClass().getName() + ": " + t;
        }
    }

    private void unregisterApp(String id) {
        apps.remove(id);
        externalAppIds.remove(id);
        externalFileByAppId.remove(id);
    }

    private void showLoadErrors() {
        StringBuilder sb = new StringBuilder("Algunas apps no se han podido cargar:\n");
        for (String e : loadErrors) sb.append("\n• ").append(e);
        loadErrors.clear();
        showMessage(AppInfo.NAME, sb.toString());
    }

    // ------------------------------------------------------------------ interfaz: marco

    @SuppressWarnings("deprecation")
    private void buildUi() {
        Palette p = ThemeManager.palette();
        if (contentFrame != null) contentFrame.removeAllViews();
        if (homeView == null) homeView = buildHome();

        root = new FrameLayout(this);
        root.setBackgroundColor(p.titleBar);
        // Con edge-to-edge (Android 15+) la app dibuja bajo las barras del sistema: se reservan sus márgenes.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                left = i.left;
                top = i.top;
                right = i.right;
                bottom = i.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, MATCH);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.titleBar);
        bar.setPadding(dp(4), 0, dp(4), 0);

        TextView menuButton = Ui.iconButton(this, "≡", p.titleBarText, "Menú");
        menuButton.setOnClickListener(v -> openDrawer());
        titleView = Ui.text(this, AppInfo.NAME, 18, true, p.titleBarText);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(dp(8), 0, dp(8), 0);
        TextView settingsButton = Ui.iconButton(this, "⋮", p.titleBarText, "Ajustes generales");
        settingsButton.setOnClickListener(v -> openSettings());

        bar.addView(menuButton);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(settingsButton);
        main.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View divider = new View(this);
        divider.setBackgroundColor(p.border);
        main.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        contentFrame = new FrameLayout(this);
        contentFrame.setBackgroundColor(p.background);
        main.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = Ui.text(this, statusText, 12, false, p.muted);
        statusView.setPadding(dp(14), dp(6), dp(14), dp(6));
        statusView.setBackgroundColor(p.titleBar);
        statusView.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        main.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, MATCH);

        drawerWidth = Math.min(dp(300), Math.round(getResources().getDisplayMetrics().widthPixels * 0.85f));
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(p.surface);
        drawer.setElevation(dp(16));
        drawer.setClickable(true); // no deja pasar los toques al contenido
        drawer.setVisibility(View.GONE);
        drawer.setTranslationX(-drawerWidth);
        root.addView(drawer, new FrameLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerOpen = false;
        setContentView(root);
        root.requestApplyInsets();
        showActiveContent();
        updateTitle();
        updateBackCallback();
    }

    private void showActiveContent() {
        contentFrame.removeAllViews();
        View view = homeView;
        if (!HOME.equals(activeId)) {
            AppSession session = sessions.get(activeId);
            if (session != null && session.view != null) view = session.view;
        }
        if (view != null && view.getParent() == null) contentFrame.addView(view, MATCH);
    }

    private void updateTitle() {
        if (titleView == null) return;
        NeuroMobileApp app = apps.get(activeId);
        titleView.setText(HOME.equals(activeId) || app == null ? AppInfo.NAME : app.name());
    }

    private void setStatusText(String message) {
        statusText = message == null || message.trim().isEmpty() ? "" : message;
        if (statusView == null) return;
        statusView.setText(statusText);
        statusView.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void applyWindowColors(Palette p) {
        getWindow().setBackgroundDrawable(new ColorDrawable(p.titleBar));
        boolean lightBars = !p.dark;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars ? mask : 0, mask);
            }
        } else {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightBars) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (lightBars) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(p.titleBar);
            getWindow().setNavigationBarColor(p.titleBar);
        }
    }

    // ------------------------------------------------------------------ interfaz: menú lateral

    private void openDrawer() {
        if (drawerOpen) return;
        rebuildDrawer();
        drawerOpen = true;
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0f).setDuration(200);
        updateBackCallback();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!drawerOpen) scrim.setVisibility(View.GONE);
        });
        drawer.animate().translationX(-drawerWidth).setDuration(180).withEndAction(() -> {
            if (!drawerOpen) drawer.setVisibility(View.GONE);
        });
        updateBackCallback();
    }

    private void rebuildDrawer() {
        Palette p = ThemeManager.palette();
        drawer.removeAllViews();
        drawer.setPadding(dp(8), dp(20), dp(8), dp(8));

        TextView title = Ui.text(this, AppInfo.NAME.replace(" of ", " of\n"), 18, true, p.text);
        title.setPadding(dp(12), 0, dp(12), 0);
        TextView version = Ui.text(this, "Versión " + AppInfo.version(this), 12, false, p.muted);
        version.setPadding(dp(12), dp(2), dp(12), dp(16));
        drawer.addView(title);
        drawer.addView(version);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // Inicio siempre está visible. Las apps aparecen debajo solo mientras tienen una sesión abierta.
        list.addView(navRow(p, "Inicio", new BadgeDrawable("I", HOME, dp(28)), false,
                HOME.equals(activeId), () -> showApp(HOME), null));

        boolean any = false;
        for (AppSession session : sessions.values()) {
            if (session.closing) continue;
            if (!any) {
                TextView header = Ui.text(this, "APPS ABIERTAS", 11, true, p.muted);
                header.setPadding(dp(12), dp(16), dp(12), dp(4));
                list.addView(header);
                any = true;
            }
            final String id = session.id;
            list.addView(navRow(p, session.app.name(), iconFor(session.app, dp(28)), true,
                    id.equals(activeId), () -> showApp(id), () -> requestCloseApp(id)));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        drawer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        drawer.addView(navRow(p, "Ajustes generales", null, false, false, this::openSettings, null));
    }

    private View navRow(Palette p, String label, Drawable icon, boolean closable, boolean selected,
                        Runnable onClick, Runnable onClose) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Ui.ripple(selected ? Ui.withAlpha(p.primary, 0x2E) : Color.TRANSPARENT,
                Ui.withAlpha(p.text, 0x22), dp(12), 0, 0));

        View marker = new View(this);
        marker.setBackgroundColor(selected ? p.primary : Color.TRANSPARENT);
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(3), dp(24));
        markerParams.setMargins(dp(4), 0, dp(10), 0);
        row.addView(marker, markerParams);

        if (icon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(28), dp(28));
            imageParams.rightMargin = dp(12);
            row.addView(image, imageParams);
        }

        TextView text = Ui.text(this, label, 15, selected, p.text);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (closable && onClose != null) {
            TextView close = Ui.iconButton(this, "×", p.muted, "Cerrar " + label);
            close.setOnClickListener(v -> onClose.run());
            row.addView(close);
        }

        row.setOnClickListener(v -> {
            closeDrawer();
            onClick.run();
        });
        return row;
    }

    // ------------------------------------------------------------------ interfaz: inicio

    private View buildHome() {
        Palette p = ThemeManager.palette();
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView heading = Ui.text(this, "Tus apps", 24, true, p.text);
        TextView sub = Ui.text(this, "Elige una para empezar.", 14, false, p.muted);
        sub.setPadding(0, dp(4), 0, dp(16));
        column.addView(heading);
        column.addView(sub);

        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 2 : 1;
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        for (NeuroMobileApp app : apps.values()) {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(buildCard(p, app), params);
        }
        column.addView(grid);

        TextView hint = Ui.text(this, "Puedes instalar o desinstalar apps desde Ajustes generales → Gestionar apps.",
                12, false, p.muted);
        hint.setPadding(0, dp(20), 0, 0);
        column.addView(hint);

        scroll.addView(column);
        return scroll;
    }

    private View buildCard(Palette p, NeuroMobileApp app) {
        final String id = app.id();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(Ui.ripple(p.surface, Ui.withAlpha(p.text, 0x22), dp(16), p.border, 1));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(iconFor(app, dp(44)));
        card.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        text.addView(Ui.text(this, app.name(), 16, true, p.text));
        String description = safeDescription(app);
        if (!description.isEmpty()) text.addView(Ui.text(this, description, 13, false, p.text));
        TextView kind = Ui.text(this, externalAppIds.contains(id)
                ? "App externa, versión " + safeVersion(app) : "Integrado", 12, false, p.muted);
        kind.setPadding(0, dp(6), 0, 0);
        text.addView(kind);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(v -> showApp(id));
        return card;
    }

    private Drawable iconFor(NeuroMobileApp app, int sizePx) {
        Drawable fallback;
        try {
            fallback = app.icon();
            if (fallback == null) fallback = new BadgeDrawable(app.name(), app.id(), sizePx);
        } catch (Throwable ignored) {
            // una app con un icono roto no debe romper el panel
            fallback = new BadgeDrawable(app.name(), app.id(), sizePx);
        }
        return ThemeManager.appIcon(app.id(), fallback, sizePx);
    }

    private static String safeVersion(NeuroMobileApp app) {
        try {
            return app.version();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeDescription(NeuroMobileApp app) {
        try {
            String d = app.description();
            return d == null ? "" : d;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ sesiones de apps

    /** Una app abierta: cada una tiene su propio hilo para onStart()/onStop(). */
    private static final class PendingAppRequest {
        final String appId;
        final int originalRequestCode;

        PendingAppRequest(String appId, int originalRequestCode) {
            this.appId = appId;
            this.originalRequestCode = originalRequestCode;
        }
    }

    private static final class AppSession {
        final String id;
        final NeuroMobileApp app;
        final MobileContext context;
        final ExecutorService worker;
        volatile boolean closing;
        /** createView() terminó bien y la app puede recibir onResume/onPause. */
        boolean ready;
        boolean resumedCalled;
        View view;

        AppSession(String id, NeuroMobileApp app, MobileContext context) {
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

    private void showApp(String id) {
        if (HOME.equals(id)) {
            setActive(HOME);
            return;
        }
        launchApp(id, true);
    }

    /** Inicia una app; activate=false permite arrancarla sin abandonar Inicio. */
    private void launchApp(String id, boolean activate) {
        NeuroMobileApp app = apps.get(id);
        if (app == null) return;

        AppSession existing = sessions.get(id);
        if (existing != null) {
            if (existing.closing) {
                if (activate) setStatusText("La aplicación se está cerrando…");
                return;
            }
            if (activate) setActive(id);
            return;
        }

        AppSession session = new AppSession(id, app, new MobileContext(app));
        session.view = loadingView(app.name());
        sessions.put(id, session);
        if (activate) setActive(id);

        // onStart() en el hilo de la app; la vista se crea después en el hilo principal.
        try {
            session.worker.execute(() -> {
                Throwable failure = null;
                try {
                    app.onStart(session.context);
                } catch (Throwable t) {
                    failure = t;
                }
                final Throwable startFailure = failure;
                runOnUiThread(() -> finishOpening(session, startFailure));
            });
        } catch (RejectedExecutionException ex) {
            sessions.remove(id);
        }
    }

    private void finishOpening(AppSession session, Throwable startFailure) {
        if (sessions.get(session.id) != session || session.closing) return;

        if (startFailure != null) {
            Log.e(TAG, "onStart falló en " + session.id, startFailure);
            session.view = errorView(session.app.name(), startFailure);
            session.ready = false;
            setStatusText("No se pudo iniciar " + session.app.name() + ".");
        } else {
            rebuildSessionView(session);
        }
        if (session.id.equals(activeId)) showActiveContent();
        dispatchResume(session);
    }

    private void rebuildSessionView(AppSession session) {
        try {
            View view = session.app.createView(session.context, LayoutInflater.from(this), contentFrame);
            if (view == null) throw new IllegalStateException("createView devolvió null");
            session.view = view;
            session.ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "createView falló en " + session.id, t);
            session.view = errorView(session.app.name(), t);
            session.ready = false;
            setStatusText("No se pudo abrir " + session.app.name() + ".");
        }
    }

    private void setActive(String id) {
        String previous = activeId;
        if (!previous.equals(id)) dispatchPause(sessions.get(previous));
        activeId = id;
        showActiveContent();
        updateTitle();
        updateBackCallback();
        setStatusText("");
        dispatchResume(sessions.get(id));
    }

    private void dispatchResume(AppSession session) {
        if (session == null || !session.ready || session.closing || session.resumedCalled
                || !activityResumed || !session.id.equals(activeId)) return;
        session.resumedCalled = true;
        try {
            session.app.onResume();
        } catch (Throwable t) {
            Log.e(TAG, "onResume falló en " + session.id, t);
        }
    }

    private void dispatchPause(AppSession session) {
        if (session == null || !session.resumedCalled) return;
        session.resumedCalled = false;
        try {
            session.app.onPause();
        } catch (Throwable t) {
            Log.e(TAG, "onPause falló en " + session.id, t);
        }
    }

    private void requestCloseApp(String id) {
        AppSession session = sessions.get(id);
        if (session == null || session.closing) return;
        if (hasUnsavedChanges(session.app)) {
            Ui.show(Ui.dialog(this)
                    .setTitle("Posibles cambios sin guardar")
                    .setMessage("Esta aplicación indica que puede tener cambios sin guardar.")
                    .setPositiveButton("Mantener abierta", null)
                    .setNegativeButton("Cerrar de todos modos", (d, w) -> closeApp(session)));
            return;
        }
        closeApp(session);
    }

    private void closeApp(AppSession session) {
        if (session.closing) return;
        dispatchPause(session);
        session.closing = true;
        pendingAppRequests.entrySet().removeIf(entry -> entry.getValue().appId.equals(session.id));
        if (session.id.equals(activeId)) setActive(HOME);
        setStatusText("Cerrando " + session.app.name() + "…");
        if (drawerOpen) rebuildDrawer();

        try {
            // onStop() se ejecuta en el mismo hilo de la app y después de onStart().
            session.worker.execute(() -> {
                try {
                    session.app.onStop();
                } catch (Throwable t) {
                    Log.e(TAG, "onStop falló en " + session.id, t);
                } finally {
                    session.worker.shutdown();
                    runOnUiThread(() -> {
                        if (sessions.get(session.id) == session) sessions.remove(session.id);
                        if (statusText.equals("Cerrando " + session.app.name() + "…")) setStatusText("");
                        if (drawerOpen) rebuildDrawer();
                    });
                }
            });
        } catch (RejectedExecutionException ex) {
            sessions.remove(session.id);
        }
    }

    /** Parada sin tocar la interfaz (la actividad se destruye). */
    private void stopSession(AppSession session) {
        if (session.closing) return;
        session.closing = true;
        try {
            session.worker.execute(() -> {
                try {
                    session.app.onStop();
                } catch (Throwable t) {
                    Log.e(TAG, "onStop falló en " + session.id, t);
                } finally {
                    session.worker.shutdown();
                }
            });
        } catch (RejectedExecutionException ex) {
            session.worker.shutdownNow();
        }
    }

    /**
     * Convención opcional heredada del escritorio: public static volatile boolean UNSAVED_CHANGES.
     * Si no existe, o vale false, el cierre no pide confirmación.
     */
    private static boolean hasUnsavedChanges(NeuroMobileApp app) {
        for (Class<?> type = app.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            final Field field;
            try {
                field = type.getDeclaredField("UNSAVED_CHANGES");
            } catch (NoSuchFieldException ignored) {
                continue;
            }
            if (field.getType() != boolean.class && field.getType() != Boolean.class) return true;
            try {
                field.setAccessible(true);
                Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : app);
                return Boolean.TRUE.equals(value);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return true; // prioriza no perder datos
            }
        }
        return false;
    }

    private View loadingView(String name) {
        Palette p = ThemeManager.palette();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(p.primary));
        box.addView(progress);
        TextView label = Ui.text(this, "Iniciando " + name + "…", 15, false, p.muted);
        label.setPadding(0, dp(12), 0, 0);
        box.addView(label);
        return box;
    }

    private View errorView(String name, Throwable t) {
        Palette p = ThemeManager.palette();
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextView area = Ui.text(this, "«" + name + "» no se ha podido abrir.\n\n" + sw, 12, false, p.text);
        area.setTypeface(Typeface.MONOSPACE);
        area.setTextIsSelectable(true);
        area.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(area);
        return scroll;
    }

    /** Lo que cada app recibe del panel. */
    private final class MobileContext implements NeuroMobileContext {
        private final NeuroMobileApp app;
        private PropertiesStore store;

        MobileContext(NeuroMobileApp app) {
            this.app = app;
        }

        @Override public Path dataDir() { return AppPaths.appDataDir(MainActivity.this, app.id()); }

        private synchronized PropertiesStore store() {
            if (store == null) store = new PropertiesStore(dataDir().resolve("settings.properties"));
            return store;
        }

        private boolean external() {
            return externalAppIds.contains(app.id());
        }

        @Override
        public boolean hasPermission(String permissionId) {
            if (!NeuroMobilePermissions.isSupported(permissionId)) return false;
            if (!external()) return true;
            AppRegistry currentRegistry = registry;
            if (currentRegistry == null) return false;
            try {
                return currentRegistry.grantedPermissions(app.id()).contains(permissionId);
            } catch (RuntimeException ex) {
                Log.e(TAG, "No se pudo comprobar el permiso " + permissionId + " de " + app.id(), ex);
                return false;
            }
        }

        @Override
        public void requirePermission(String permissionId) {
            if (!NeuroMobilePermissions.isSupported(permissionId) || !hasPermission(permissionId)) {
                NeuroMobilePermissions.Permission permission = NeuroMobilePermissions.find(permissionId);
                String name = permission == null ? permissionId : permission.name();
                throw new SecurityException("La app «" + app.name() + "» no tiene concedido el permiso «"
                        + name + "». Concédelo en Ajustes → Gestionar apps → Configurar permisos.");
            }
        }

        @Override
        public URLConnection openConnection(URL url) throws IOException {
            if (url == null || url.getHost() == null
                    || (!"http".equalsIgnoreCase(url.getProtocol())
                    && !"https".equalsIgnoreCase(url.getProtocol()))) {
                throw new IllegalArgumentException("Solo se permiten conexiones HTTP o HTTPS.");
            }
            requirePermission(NeuroMobilePermissions.NETWORK_CONNECT);
            return url.openConnection();
        }

        @Override
        public ServerSocket openServerSocket(int port) throws IOException {
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Puerto no válido.");
            requirePermission(NeuroMobilePermissions.NETWORK_LISTEN);
            return new ServerSocket(port);
        }

        @Override
        public void requestDocumentRead(String mimeType, int requestCode) {
            requirePermission(NeuroMobilePermissions.FILES_READ_DOCUMENTS);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType == null || mimeType.trim().isEmpty() ? "*/*" : mimeType);
            startAppActivityForResult(app.id(), intent, requestCode);
        }

        @Override
        public void requestDocumentWrite(String mimeType, String suggestedName, int requestCode) {
            requirePermission(NeuroMobilePermissions.FILES_WRITE_DOCUMENTS);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType == null || mimeType.trim().isEmpty() ? "*/*" : mimeType)
                    .putExtra(Intent.EXTRA_TITLE, suggestedName == null ? "documento" : suggestedName);
            startAppActivityForResult(app.id(), intent, requestCode);
        }

        @Override
        public InputStream openDocumentInputStream(Uri uri) throws IOException {
            requirePermission(NeuroMobilePermissions.FILES_READ_DOCUMENTS);
            if (uri == null) throw new IllegalArgumentException("URI no válido.");
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("No se pudo abrir el documento.");
            return in;
        }

        @Override
        public OutputStream openDocumentOutputStream(Uri uri) throws IOException {
            requirePermission(NeuroMobilePermissions.FILES_WRITE_DOCUMENTS);
            if (uri == null) throw new IllegalArgumentException("URI no válido.");
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) throw new IOException("No se pudo abrir el documento para escritura.");
            return out;
        }

        @Override
        public CharSequence readClipboardText() {
            requirePermission(NeuroMobilePermissions.CLIPBOARD_ACCESS);
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) return null;
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            return clip.getItemAt(0).coerceToText(MainActivity.this);
        }

        @Override
        public void writeClipboardText(CharSequence text) {
            requirePermission(NeuroMobilePermissions.CLIPBOARD_ACCESS);
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null) throw new IllegalStateException("El portapapeles no está disponible.");
            manager.setPrimaryClip(ClipData.newPlainText("NeuroCenter", text == null ? "" : text));
        }

        @Override
        public void saveSecret(String key, char[] secret) throws IOException {
            requirePermission(NeuroMobilePermissions.CREDENTIALS_ACCESS);
            AndroidCredentialVault.save(MainActivity.this, app.id(), key, secret);
        }

        @Override
        public char[] loadSecret(String key) throws IOException {
            requirePermission(NeuroMobilePermissions.CREDENTIALS_ACCESS);
            return AndroidCredentialVault.load(MainActivity.this, app.id(), key);
        }

        @Override
        public void deleteSecret(String key) throws IOException {
            requirePermission(NeuroMobilePermissions.CREDENTIALS_ACCESS);
            AndroidCredentialVault.delete(MainActivity.this, app.id(), key);
        }

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            startAppActivityForResult(app.id(), intent, requestCode);
        }

        @Override
        public void requestPermissions(String[] permissions, int requestCode) {
            if (permissions == null || permissions.length == 0) {
                throw new IllegalArgumentException("No se ha indicado ningún permiso Android.");
            }
            for (String permission : permissions) {
                if (Manifest.permission.BLUETOOTH_CONNECT.equals(permission)
                        || Manifest.permission.BLUETOOTH_ADVERTISE.equals(permission)) {
                    requirePermission(NeuroMobilePermissions.BLUETOOTH_ACCESS);
                } else if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                    requirePermission(NeuroMobilePermissions.NOTIFICATIONS_ACCESS);
                }
            }
            startAppPermissionRequest(app.id(), permissions, requestCode);
        }

        @Override public String getSetting(String key, String defaultValue) { return store().get(key, defaultValue); }

        @Override public void putSetting(String key, String value) { store().put(key, value); }

        @Override public Activity activity() { return MainActivity.this; }

        @Override public Context applicationContext() { return getApplicationContext(); }

        @Override public void setStatus(String message) {
            runOnUiThread(() -> setStatusText(message));
        }

        @Override public void openApp(String appId) {
            runOnUiThread(() -> {
                if (apps.containsKey(appId)) showApp(appId);
            });
        }

        @Override public int themeColor(String key, int fallback) {
            return ThemeManager.palette().color(key, fallback);
        }

        @Override public boolean isDarkTheme() { return ThemeManager.palette().dark; }
    }

    // ------------------------------------------------------------------ resultados iniciados por apps

    private int allocateAppRequestCode() {
        for (int attempts = 0; attempts < 0x0FFF; attempts++) {
            int candidate = nextAppRequestCode++;
            if (nextAppRequestCode > 0x7FFE) nextAppRequestCode = 0x7000;
            if (!pendingAppRequests.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("Hay demasiadas solicitudes Android pendientes.");
    }

    private void startAppActivityForResult(String appId, Intent intent, int originalRequestCode) {
        if (intent == null) throw new IllegalArgumentException("Intent no válido.");
        if (originalRequestCode < 0 || originalRequestCode > 0xFFFF) {
            throw new IllegalArgumentException("El código de solicitud debe estar entre 0 y 65535.");
        }
        runOnUiThread(() -> {
            AppSession session = sessions.get(appId);
            if (session == null || session.closing) {
                throw new IllegalStateException("La app ya no está abierta.");
            }
            int hostCode = allocateAppRequestCode();
            pendingAppRequests.put(hostCode, new PendingAppRequest(appId, originalRequestCode));
            try {
                MainActivity.this.startActivityForResult(intent, hostCode);
            } catch (RuntimeException ex) {
                pendingAppRequests.remove(hostCode);
                throw ex;
            }
        });
    }

    private void startAppPermissionRequest(String appId, String[] permissions, int originalRequestCode) {
        if (originalRequestCode < 0 || originalRequestCode > 0xFFFF) {
            throw new IllegalArgumentException("El código de solicitud debe estar entre 0 y 65535.");
        }
        String[] copy = permissions.clone();
        runOnUiThread(() -> {
            AppSession session = sessions.get(appId);
            if (session == null || session.closing) {
                throw new IllegalStateException("La app ya no está abierta.");
            }
            int hostCode = allocateAppRequestCode();
            pendingAppRequests.put(hostCode, new PendingAppRequest(appId, originalRequestCode));
            try {
                MainActivity.this.requestPermissions(copy, hostCode);
            } catch (RuntimeException ex) {
                pendingAppRequests.remove(hostCode);
                throw ex;
            }
        });
    }

    // ------------------------------------------------------------------ retroceso

    private boolean handleBack() {
        if (drawerOpen) {
            closeDrawer();
            return true;
        }
        if (!HOME.equals(activeId)) {
            setActive(HOME);
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    /**
     * Con targetSdk 36 el sistema ya no llama a onBackPressed(): se registra un callback solo
     * mientras hay algo que retroceder (menú abierto o app activa); en Inicio se deja el
     * comportamiento del sistema (salir).
     */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean needed = drawerOpen || !HOME.equals(activeId);
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (needed && backCallback == null) {
            OnBackInvokedCallback callback = () -> handleBack();
            backCallback = callback;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        } else if (!needed && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    // ------------------------------------------------------------------ resultados de actividades

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_APP || requestCode == REQUEST_INSTALL_THEME) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                if (requestCode == REQUEST_INSTALL_APP) confirmInstallApp(uri);
                else installTheme(uri);
            }
            return;
        }
        PendingAppRequest pending = pendingAppRequests.remove(requestCode);
        if (pending == null) return;
        AppSession session = sessions.get(pending.appId);
        if (session != null && session.ready && !session.closing) {
            session.app.onActivityResult(pending.originalRequestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PendingAppRequest pending = pendingAppRequests.remove(requestCode);
        if (pending == null) return;
        AppSession session = sessions.get(pending.appId);
        if (session != null && session.ready && !session.closing) {
            session.app.onRequestPermissionsResult(pending.originalRequestCode, permissions, grantResults);
        }
    }

    // ------------------------------------------------------------------ ajustes generales

    private void openSettings() {
        String[] labels = {"Cambiar tema", "Gestionar temas", "Gestionar apps"};
        Ui.show(Ui.dialog(this)
                .setTitle("Ajustes generales")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) chooseTheme();
                    else if (which == 1) manageThemes();
                    else manageApps();
                })
                .setNegativeButton("Cerrar", null));
    }

    // ---- temas

    private void chooseTheme() {
        List<ThemeManager.ThemeInfo> themes = ThemeManager.availableThemes();
        String[] names = new String[themes.size()];
        String currentId = ThemeManager.activeThemeId();
        int selected = 0;
        for (int i = 0; i < themes.size(); i++) {
            names[i] = themes.get(i).name;
            if (themes.get(i).id.equals(currentId)) selected = i;
        }
        Ui.show(Ui.dialog(this)
                .setTitle("Temas")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    dialog.dismiss();
                    applyThemeChoice(themes.get(which).id);
                })
                .setNegativeButton("Cancelar", null));
    }

    private void applyThemeChoice(String themeId) {
        if (themeId.equals(ThemeManager.activeThemeId())) return;
        try {
            // Aplicar primero: un tema incompatible no debe quedar guardado como preferencia.
            ThemeManager.apply(themeId);
            settings.put("theme", ThemeManager.activeThemeId());
            onThemeChanged();
        } catch (IllegalArgumentException ex) {
            showMessage("Tema no compatible", ex.getMessage());
        }
    }

    /** Reaplica el tema: las vistas de las apps abiertas se reconstruyen con createView(). */
    private void onThemeChanged() {
        Palette p = ThemeManager.palette();
        AppSession active = sessions.get(activeId);
        dispatchPause(active);
        if (contentFrame != null) contentFrame.removeAllViews();
        for (AppSession session : new ArrayList<>(sessions.values())) {
            if (!session.closing && session.ready) rebuildSessionView(session);
        }
        homeView = buildHome();
        applyWindowColors(p);
        buildUi();
        dispatchResume(active);
    }

    private void manageThemes() {
        String[] labels = {"Instalar tema (.tme)", "Desinstalar tema"};
        Ui.show(Ui.dialog(this)
                .setTitle("Gestor de temas")
                .setMessage("Los temas son paquetes de apariencia. Solo se admiten archivos .tme con JSON y "
                        + "recursos; no ejecutan código. El tema debe declarar compatibilidad con móvil.")
                .setPositiveButton(labels[0], (d, w) -> pickFile(REQUEST_INSTALL_THEME))
                .setNegativeButton(labels[1], (d, w) -> uninstallTheme())
                .setNeutralButton("Volver", null));
    }

    private void installTheme(Uri uri) {
        String name = displayName(uri);
        setStatusText("Instalando " + name + "…");
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String id = ThemeArchive.install(this, in, name);
                runOnUiThread(() -> {
                    setStatusText("");
                    Ui.show(Ui.dialog(this)
                            .setTitle("Tema instalado")
                            .setMessage("Tema «" + id + "» instalado. ¿Quieres aplicarlo ahora?")
                            .setPositiveButton("Aplicar", (d, w) -> applyThemeChoice(id))
                            .setNegativeButton("Más tarde", null));
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setStatusText("");
                    showMessage("Error al instalar", "No se pudo instalar el tema: " + ex.getMessage());
                });
            }
        });
    }

    private void uninstallTheme() {
        List<ThemeManager.ThemeInfo> installed = new ArrayList<>();
        for (ThemeManager.ThemeInfo theme : ThemeManager.availableThemes()) {
            if (!theme.builtIn()) installed.add(theme);
        }
        if (installed.isEmpty()) {
            showMessage("Gestor de temas", "No hay temas externos instalados.");
            return;
        }
        String[] names = new String[installed.size()];
        for (int i = 0; i < names.length; i++) names[i] = installed.get(i).name;
        Ui.show(Ui.dialog(this)
                .setTitle("Desinstalar tema")
                .setItems(names, (dialog, which) -> {
                    ThemeManager.ThemeInfo selected = installed.get(which);
                    confirm("Confirmar desinstalación",
                            "Se eliminará el tema «" + selected.name + "». ¿Continuar?", "Desinstalar",
                            () -> doUninstallTheme(selected));
                })
                .setNegativeButton("Cancelar", null));
    }

    private void doUninstallTheme(ThemeManager.ThemeInfo selected) {
        try {
            boolean active = selected.id.equals(ThemeManager.activeThemeId());
            ThemeArchive.uninstall(this, selected.id);
            if (active) {
                settings.put("theme", "light");
                ThemeManager.apply("light");
                onThemeChanged();
            }
            showMessage("Gestor de temas", "Tema desinstalado.");
        } catch (IOException ex) {
            showMessage("Error al desinstalar", "No se pudo desinstalar el tema: " + ex.getMessage());
        }
    }

    // ---- apps externas

    private void manageApps() {
        if (registry == null) {
            showMessage("Gestionar apps", "El registro SQLite de aplicaciones no está disponible.");
            return;
        }
        String[] labels = {"Instalar app (.apk / .jar)", "Desinstalar app",
                "Configurar inicio automático", "Configurar permisos"};
        Ui.show(Ui.dialog(this)
                .setTitle("Gestor de apps")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) pickFile(REQUEST_INSTALL_APP);
                    else if (which == 1) uninstallExternalApp();
                    else if (which == 2) configureAutorun();
                    else configureAppPermissions();
                })
                .setNegativeButton("Cerrar", null));
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException ex) {
            showMessage("Selector de archivos", "No hay ningún selector de archivos disponible: " + ex.getMessage());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // se usa el último segmento de la ruta
        }
        String last = uri.getLastPathSegment();
        return last == null ? "paquete" : last;
    }

    private void confirmInstallApp(Uri uri) {
        String name = displayName(uri);
        confirm("Instalar app",
                "¿Instalar «" + name + "»?\n\nEl código DEX se ejecuta dentro del proceso de NeuroCenter. "
                        + "Los permisos de NeuroMobileContext controlan las operaciones realizadas mediante "
                        + "la API del host, pero Android no aísla clases cargadas dinámicamente: una app externa "
                        + "maliciosa podría acceder directamente a las API y permisos del proceso. Instala solo "
                        + "código de fuentes en las que confíes.",
                "Instalar", () -> installApp(uri, name));
    }

    private void installApp(Uri uri, String name) {
        if (registry == null) {
            showMessage("Gestionar apps", "El registro SQLite de aplicaciones no está disponible.");
            return;
        }
        setStatusText("Instalando " + name + "…");
        final AppRegistry.AppRecord[] installed = new AppRegistry.AppRecord[1];
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                installed[0] = AppLoader.install(this, registry, in, name);
                AppLoader.Result result = AppLoader.load(this, Collections.singletonList(installed[0].fileName));
                runOnUiThread(() -> finishInstall(installed[0], result));
            } catch (Exception ex) {
                AppRegistry.AppRecord partial = installed[0];
                if (partial != null) {
                    try {
                        AppLoader.uninstall(this, registry, partial);
                    } catch (IOException ignored) {
                        // sin más que hacer
                    }
                }
                runOnUiThread(() -> {
                    setStatusText("");
                    showMessage("Error al instalar", "No se pudo instalar la app: " + ex.getMessage());
                });
            }
        });
    }

    private void finishInstall(AppRegistry.AppRecord record, AppLoader.Result result) {
        List<String> problems = new ArrayList<>(result.errors);
        List<String> addedIds = new ArrayList<>();
        if (problems.isEmpty() && result.apps.isEmpty()) problems.add("El paquete no declara ninguna app.");
        if (problems.isEmpty()) {
            for (AppLoader.LoadedApp loaded : result.apps) {
                String error = registerApp(loaded.app, true, loaded.fileName);
                if (error != null) problems.add(error);
                else addedIds.add(loaded.app.id());
            }
        }

        setStatusText("");
        if (!problems.isEmpty()) {
            // Se deshace la instalación para no dejar un paquete que no se puede abrir.
            for (String id : addedIds) unregisterApp(id);
            ioExecutor.execute(() -> {
                try {
                    AppLoader.uninstall(this, registry, record);
                } catch (IOException ignored) {
                    // sin más que hacer
                }
            });
            showMessage("Error al instalar", "No se pudo instalar la app:\n\n• " + TextUtils.join("\n• ", problems));
            return;
        }

        homeView = buildHome();
        if (HOME.equals(activeId)) showActiveContent();
        showMessage("App instalada", "«" + record.name + "» ya está disponible en Inicio.\n"
                + "El inicio automático se puede configurar desde Gestionar apps.");
    }

    private void uninstallExternalApp() {
        List<AppRegistry.AppRecord> installed = registry.listApps();
        if (installed.isEmpty()) {
            showMessage("Gestionar apps", "No hay apps externas instaladas.");
            return;
        }
        String[] names = new String[installed.size()];
        for (int i = 0; i < names.length; i++) names[i] = installed.get(i).name + " (" + installed.get(i).version + ")";
        Ui.show(Ui.dialog(this)
                .setTitle("Desinstalar app")
                .setItems(names, (dialog, which) -> {
                    AppRegistry.AppRecord selected = installed.get(which);
                    confirm("Confirmar desinstalación",
                            "Se eliminará «" + selected.name + "» y se cerrará si está abierta. ¿Continuar?",
                            "Desinstalar", () -> doUninstallApp(selected));
                })
                .setNegativeButton("Cancelar", null));
    }

    private void doUninstallApp(AppRegistry.AppRecord record) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, String> entry : externalFileByAppId.entrySet()) {
            if (entry.getValue().equals(record.fileName)) ids.add(entry.getKey());
        }
        for (String id : ids) {
            AppSession session = sessions.get(id);
            if (session != null) closeApp(session);
            unregisterApp(id);
        }
        homeView = buildHome();
        if (HOME.equals(activeId)) showActiveContent();

        ioExecutor.execute(() -> {
            try {
                AppLoader.uninstall(this, registry, record);
                runOnUiThread(() -> showMessage("Gestionar apps", "«" + record.name + "» desinstalada."));
            } catch (IOException | RuntimeException ex) {
                runOnUiThread(() -> showMessage("Error al desinstalar",
                        "No se pudo desinstalar la app: " + ex.getMessage()));
            }
        });
    }

    private void configureAppPermissions() {
        List<AppRegistry.AppRecord> installed = registry.listApps();
        if (installed.isEmpty()) {
            showMessage("Permisos de aplicaciones", "No hay apps externas instaladas.");
            return;
        }
        String[] names = new String[installed.size()];
        for (int i = 0; i < installed.size(); i++) {
            AppRegistry.AppRecord record = installed.get(i);
            Set<String> grants = registry.grantedPermissions(record.id);
            names[i] = record.name + " — " + NeuroMobilePermissions.summary(grants);
        }
        Ui.show(Ui.dialog(this)
                .setTitle("Permisos de aplicaciones")
                .setItems(names, (dialog, which) -> editPermissions(installed.get(which)))
                .setNegativeButton("Cerrar", null));
    }

    private void editPermissions(AppRegistry.AppRecord app) {
        Set<String> current = new HashSet<>(registry.grantedPermissions(app.id));
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(18), dp(8), dp(18), dp(8));
        Map<String, android.widget.CheckBox> controls = new LinkedHashMap<>();

        for (NeuroMobilePermissions.Permission permission : NeuroMobilePermissions.supported()) {
            android.widget.CheckBox checkbox = new android.widget.CheckBox(this);
            checkbox.setText(permission.name());
            checkbox.setChecked(current.contains(permission.id()));
            checkbox.setTextColor(ThemeManager.palette().text);
            controls.put(permission.id(), checkbox);
            TextView description = Ui.text(this, permission.description(), 12, false,
                    ThemeManager.palette().muted);
            description.setPadding(dp(34), 0, 0, dp(10));
            options.addView(checkbox);
            options.addView(description);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
        AlertDialog dialog = Ui.dialog(this)
                .setTitle("Permisos de «" + app.name + "»")
                .setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                Set<String> updated = new HashSet<>();
                controls.forEach((id, checkbox) -> {
                    if (checkbox.isChecked()) updated.add(id);
                });
                try {
                    registry.setGrantedPermissions(app.id, updated);
                    dialog.dismiss();
                    showMessage("Permisos guardados",
                            "Se han guardado los permisos de «" + app.name + "».\n\nConcedidos: "
                                    + NeuroMobilePermissions.summary(updated)
                                    + "\n\nSe aplican inmediatamente a las operaciones realizadas mediante "
                                    + "NeuroMobileContext. No son un aislamiento del proceso Android: instala "
                                    + "solo código externo de confianza.");
                } catch (RuntimeException ex) {
                    showMessage("Error de permisos", "No se pudieron guardar: " + ex.getMessage());
                }
            });
        });
        dialog.show();
        Ui.style(dialog);
    }

    private void configureAutorun() {
        List<AppRegistry.AppRecord> installed = registry.listApps();
        if (installed.isEmpty()) {
            showMessage("Gestionar apps", "No hay apps externas instaladas.");
            return;
        }
        String[] names = new String[installed.size()];
        for (int i = 0; i < names.length; i++) names[i] = installed.get(i).toString();
        Ui.show(Ui.dialog(this)
                .setTitle("Inicio automático")
                .setItems(names, (dialog, which) -> {
                    AppRegistry.AppRecord selected = installed.get(which);
                    boolean enable = !selected.autorun;
                    confirm("Inicio automático",
                            "¿Quieres que «" + selected.name + "» se "
                                    + (enable ? "inicie automáticamente al abrir el panel"
                                    : "deje de iniciarse automáticamente") + "?",
                            "Sí", () -> {
                                registry.setAutorun(selected.id, enable);
                                if (enable) autorunFiles.add(selected.fileName);
                                else autorunFiles.remove(selected.fileName);
                                showMessage("Inicio automático", enable
                                        ? "La aplicación se iniciará automáticamente la próxima vez."
                                        : "Se ha desactivado el inicio automático.");
                            });
                })
                .setNegativeButton("Cancelar", null));
    }

    // ------------------------------------------------------------------ utilidades

    private void showMessage(String title, String message) {
        Ui.show(Ui.dialog(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Aceptar", null));
    }

    private void confirm(String title, String message, String positive, Runnable onYes) {
        AlertDialog.Builder builder = Ui.dialog(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(positive, (d, w) -> onYes.run());
        Ui.show(builder);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
