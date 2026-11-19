package com.neurocenter.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neurocenter.api.INcappWorker;
import com.neurocenter.apps.agenda.NcappHostServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Convierte JSON declarado por una .ncapp en controles Android normales,
 * sin permitir que la clase Java acceda a Views ni al proceso principal.
 */
public final class NcappUiController {
    private static final Map<Activity, List<Session>> ACTIVE = new WeakHashMap<>();
    private static final String LOG_TAG = "NeuroCenter-NCAPP";

    private NcappUiController() {}

    public static void open(Activity activity, NcappManager.InstalledApp app) {
        Session session = new Session(activity, app);
        synchronized (ACTIVE) {
            ACTIVE.computeIfAbsent(activity, ignored -> new ArrayList<>()).add(session);
        }
        session.start();
    }

    public static void closeAll(Activity activity) {
        List<Session> sessions;
        synchronized (ACTIVE) {
            sessions = ACTIVE.remove(activity);
        }
        if (sessions != null) for (Session session : new ArrayList<>(sessions)) session.close();
    }

    private static final class Session {
        private final Activity activity;
        private final NcappManager.InstalledApp app;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private Runnable watchdog;
        private final LinearLayout panel;
        private final AlertDialog dialog;
        private ServiceConnection connection;
        private INcappWorker worker;
        private boolean bound;
        private volatile boolean closed;
        private volatile boolean failed;
        private int remainingHostRequests = 8;

        Session(Activity activity, NcappManager.InstalledApp app) {
            this.activity = activity;
            this.app = app;
            panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(16), dp(12), dp(16), dp(12));
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(panel);
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(app.name())
                    .setView(scroll)
                    .setPositiveButton("Cerrar", (d, w) -> close())
                    .create();
            dialog.setOnDismissListener(ignored -> close());
        }

        void start() {
            showText("Creando proceso aislado de Android…");
            dialog.show();
            armTimeout(20000, "No se ha podido conectar con el proceso aislado. "
                    + "Puede haberse cerrado durante Application.onCreate u onBind. "
                    + "Consulta el registro de Android.");
            Intent intent = new Intent(activity, NcappWorkerService.class);
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (closed || failed) return;
                    Log.i(LOG_TAG, "Proceso aislado conectado: " + name.flattenToShortString());
                    worker = INcappWorker.Stub.asInterface(binder);
                    armTimeout(180000, "Se ha agotado el tiempo preparando OpenJDK 21 "
                            + "o esperando la ejecución de HotSpot. Consulta logcat.");
                    showText("Proceso aislado conectado. Comprobando el paquete…");
                    executor.execute(Session.this::launch);
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    worker = null;
                    error("El proceso aislado se ha cerrado antes de responder. "
                            + "Consulta AndroidRuntime y NeuroCenter-Sandbox en logcat.");
                }

                @Override public void onNullBinding(ComponentName name) {
                    error("El servicio aislado no ha devuelto un enlace Binder.");
                }

                @Override public void onBindingDied(ComponentName name) {
                    error("Se ha perdido la conexión con el servicio aislado de Android.");
                }
            };
            try {
                bound = activity.bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                        "ncapp_" + UUID.randomUUID().toString().replace("-", ""),
                        activity.getMainExecutor(), connection);
                if (!bound) error("Android no ha podido crear el proceso aislado.");
            } catch (RuntimeException ex) {
                error("Error creando el sandbox: " + ex.getMessage());
            }
        }

        void launch() {
            try {
                showText("Validando el archivo .ncapp instalado…");
                NcappManager.InstalledApp current = NcappManager.inspect(app.archive());
                if (!current.id().equals(app.id())
                        || !current.mobileClass().equals(app.mobileClass())
                        || !current.sha256().equals(app.sha256())) {
                    throw new SecurityException("El archivo instalado cambió desde que se eligió.");
                }
                if (worker == null || !worker.isIsolated()) {
                    throw new SecurityException("El proceso Java no tiene un UID aislado.");
                }
                showText("Preparando la imagen de OpenJDK 21… "
                        + "En la primera ejecución puede necesitar descomprimir sus módulos.");
                try (ParcelFileDescriptor home = Java21Runtime.openSandboxHome(activity);
                     ParcelFileDescriptor archive = ParcelFileDescriptor.open(
                             app.archive(), ParcelFileDescriptor.MODE_READ_ONLY)) {
                    showText("Lanzando HotSpot Java 21 dentro del proceso aislado…");
                    String output = worker.launchApp(home, archive, app.mobileClass(),
                            app.sha256(), activity.getApplicationInfo().nativeLibraryDir);
                    disarmTimeout();
                    present(output);
                }
            } catch (Exception ex) {
                error("No se pudo arrancar el .ncapp con Java 21: " + ex);
            }
        }

        void action(String id) {
            if (closed || failed || worker == null) return;
            showText("Ejecutando «" + id + "»…");
            executor.execute(() -> {
                remainingHostRequests = 8;
                try {
                    present(worker.dispatchAction(id));
                } catch (Exception ex) {
                    error("La aplicación produjo un error: " + ex);
                }
            });
        }

        void present(String json) {
            if (closed || failed) return;
            if (json == null || json.startsWith("NCAPP_ERROR:")) {
                error(json == null ? "La JVM no devolvió una interfaz." : json);
                return;
            }
            try {
                if (json.length() > 32768) throw new IllegalArgumentException("JSON demasiado grande.");
                JSONObject screen = new JSONObject(json);
                JSONObject hostRequest = screen.optJSONObject("hostRequest");
                if (hostRequest != null) {
                    if (--remainingHostRequests < 0) {
                        throw new SecurityException("Demasiadas solicitudes consecutivas al host.");
                    }
                    String capability = hostRequest.getString("capability");
                    if (!"agenda.read".equals(capability) && !"agenda.write".equals(capability)) {
                        throw new SecurityException("Esta capacidad todavía no tiene puente móvil: "
                                + capability);
                    }
                    String requestJson = hostRequest.toString();
                    executor.execute(() -> {
                        try {
                            if (closed || worker == null) return;
                            String result = NcappHostServices.handle(activity, app, requestJson);
                            present(worker.deliverHostResponse(capability, result));
                        } catch (Exception failure) {
                            error("Error de la API de NeuroCenter: " + failure);
                        }
                    });
                    return;
                }
                remainingHostRequests = 8;
                String title = screen.optString("title", app.name());
                String message = screen.optString("text", "");
                if (title.length() > 180 || message.length() > 10000) {
                    throw new IllegalArgumentException("Texto de interfaz demasiado largo.");
                }
                JSONArray actions = screen.optJSONArray("actions");
                if (actions != null && actions.length() > 12) {
                    throw new IllegalArgumentException("Demasiados botones (máximo 12).");
                }
                List<String[]> buttons = new ArrayList<>();
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        JSONObject item = actions.getJSONObject(i);
                        String id = item.getString("id");
                        String label = item.getString("label");
                        if (!id.matches("[A-Za-z0-9._-]{1,64}") || label.isBlank()
                                || label.length() > 100) {
                            throw new IllegalArgumentException("Botón no válido.");
                        }
                        buttons.add(new String[]{id, label});
                    }
                }
                activity.runOnUiThread(() -> {
                    if (closed || failed) return;
                    dialog.setTitle(title);
                    panel.removeAllViews();
                    TextView copy = new TextView(activity);
                    copy.setText(message);
                    copy.setTextSize(16);
                    panel.addView(copy);
                    for (String[] item : buttons) {
                        Button button = new Button(activity);
                        button.setText(item[1]);
                        button.setAllCaps(false);
                        button.setOnClickListener(v -> action(item[0]));
                        panel.addView(button);
                    }
                });
            } catch (Exception ex) {
                error("La app devolvió una interfaz inválida: " + ex.getMessage());
            }
        }

        void showText(String message) {
            activity.runOnUiThread(() -> {
                if (closed || failed) return;
                panel.removeAllViews();
                TextView copy = new TextView(activity);
                copy.setText(message);
                copy.setTextSize(16);
                panel.addView(copy);
            });
        }

        /** El servicio puede morir antes de onServiceConnected: nunca dejamos la UI esperando. */
        void error(String message) {
            Log.e(LOG_TAG, "Fallo en .ncapp " + app.id() + ": " + message);
            activity.runOnUiThread(() -> {
                if (closed || failed) return;
                clearWatchdog();
                panel.removeAllViews();
                TextView copy = new TextView(activity);
                copy.setText(message);
                copy.setTextSize(16);
                panel.addView(copy);
                failed = true;
                releaseConnection();
                executor.shutdownNow();
            });
        }

        void armTimeout(long millis, String message) {
            activity.runOnUiThread(() -> {
                if (closed || failed) return;
                clearWatchdog();
                watchdog = () -> error(message);
                mainHandler.postDelayed(watchdog, millis);
            });
        }

        void disarmTimeout() {
            activity.runOnUiThread(this::clearWatchdog);
        }

        private void clearWatchdog() {
            if (watchdog != null) {
                mainHandler.removeCallbacks(watchdog);
                watchdog = null;
            }
        }

        private void releaseConnection() {
            worker = null;
            if (bound && connection != null) {
                bound = false;
                try { activity.unbindService(connection); }
                catch (IllegalArgumentException ignored) { }
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            clearWatchdog();
            executor.shutdownNow();
            releaseConnection();
            synchronized (ACTIVE) {
                List<Session> sessions = ACTIVE.get(activity);
                if (sessions != null) {
                    sessions.remove(this);
                    if (sessions.isEmpty()) ACTIVE.remove(activity);
                }
            }
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }
    }
}
