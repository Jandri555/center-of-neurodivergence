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
        private volatile int isolatedPid;
        private volatile String phase = "Solicitando proceso aislado";
        private final long createdAt = System.currentTimeMillis();
        private int remainingHostRequests = 8;
        private NcappCrashDiagnostics crashCapture;
        private String failureSummary;
        private TextView failureView;

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
                isolatedPid = worker.processPid();
                Log.i(LOG_TAG, "Worker aislado PID=" + isolatedPid + " para " + app.id());
                showText("Verificación 0/4: conexión Binder sin archivos…");
                String ping = worker.diagnosticPing();
                Log.i(LOG_TAG, "Ping del worker: " + ping);
                if (!ping.startsWith("OK:")) {
                    error("No se pudo completar la conexión Binder: " + ping);
                    return;
                }
                showText("OpenJDK 21 integrado en el APK, sin extracción privada…");
                // Separate open() calls create separate kernel file
                // descriptions. Never reuse a consumed SHA-256 stream for
                // execution: dup() would share the read offset across Binder.
                showText("Verificación 1/4: envío y SHA-256 de .ncapp…");
                String jarCheck;
                try (ParcelFileDescriptor diagnosticArchive = ParcelFileDescriptor.open(
                        app.archive(), ParcelFileDescriptor.MODE_READ_ONLY)) {
                    Log.i(LOG_TAG, "DIAG 1: enviando FD exclusivo de verificación, PID="
                            + isolatedPid);
                    jarCheck = worker.checkArchive(diagnosticArchive, app.sha256());
                }
                Log.i(LOG_TAG, "DIAG 1: " + jarCheck);
                if (!jarCheck.startsWith("OK:")) {
                    error("Error en verificación de .ncapp: " + jarCheck);
                    return;
                }
                try (ParcelFileDescriptor archive = ParcelFileDescriptor.open(
                             app.archive(), ParcelFileDescriptor.MODE_READ_ONLY)) {
                    showText("Verificación 2/4: JIMAGE alojada en el APK…");
                    Log.i(LOG_TAG, "DIAG 2: comprobando datos JIMAGE del paquete");
                    String moduleCheck = worker.checkModules();
                    Log.i(LOG_TAG, "DIAG 2: " + moduleCheck);
                    if (!moduleCheck.startsWith("OK:")) {
                        error("Error en verificación de módulos: " + moduleCheck);
                        return;
                    }
                    showText("Verificación 3/4: bibliotecas nativas OpenJDK…");
                    String nativeCheck = worker.checkNativeLibrary(
                            activity.getApplicationInfo().nativeLibraryDir);
                    Log.i(LOG_TAG, "DIAG 3: " + nativeCheck);
                    if (!nativeCheck.startsWith("OK:")) {
                        error("Error en verificación nativa: " + nativeCheck);
                        return;
                    }
                    showText("Verificación 4/4: iniciando HotSpot Java 21 aislado…");
                    showText("Lanzando HotSpot Java 21 dentro del proceso aislado…");
                    if (NcappCrashDiagnostics.enabled()) {
                        try {
                            crashCapture = NcappCrashDiagnostics.open(
                                    () -> mainHandler.post(this::refreshFailure));
                        } catch (IOException unavailable) {
                            Log.w(LOG_TAG, "Canal debug nativo no disponible", unavailable);
                        }
                    }
                    String output;
                    try {
                        output = worker.launchApp(archive, app.mobileClass(),
                                app.sha256(), activity.getApplicationInfo().nativeLibraryDir,
                                crashCapture == null ? null : crashCapture.sink());
                    } finally {
                        if (crashCapture != null) crashCapture.closeSender();
                    }
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
            phase = message;
            activity.runOnUiThread(() -> {
                if (closed || failed) return;
                panel.removeAllViews();
                TextView copy = new TextView(activity);
                copy.setText(message);
                copy.setTextSize(16);
                panel.addView(copy);
            });
        }

        /** Even when HotSpot calls exit(), kernel pipe bytes survive. */
        void error(String message) {
            String report = "NeuroCenter Android " + com.neurocenter.BuildConfig.VERSION_NAME
                    + "\nPaquete: " + app.id()
                    + "\nProceso aislado PID: "
                    + (isolatedPid > 0 ? isolatedPid : "no conectado")
                    + "\nFase: " + phase
                    + "\nError: " + message;
            Log.e(LOG_TAG, "Fallo .ncapp: " + report);
            activity.runOnUiThread(() -> {
                if (closed || failed) return;
                failed = true;
                failureSummary = report;
                clearWatchdog();
                // Preserve binding long enough to read original exit info.
                executor.shutdownNow();
                panel.removeAllViews();
                failureView = new TextView(activity);
                failureView.setTextSize(14);
                panel.addView(failureView);
                if (NcappCrashDiagnostics.enabled()) {
                    NcappCrashDiagnostics.addActions(
                            activity, panel, this::completeReport);
                }
                refreshFailure();
                mainHandler.postDelayed(this::refreshFailure, 1500);
                mainHandler.postDelayed(() -> {
                    if (!closed) releaseConnection();
                }, 4500);
            });
        }

        private String completeReport() {
            return NcappCrashDiagnostics.report(activity, failureSummary,
                    isolatedPid, createdAt, crashCapture);
        }

        private void refreshFailure() {
            if (closed || !failed || failureView == null) return;
            String report = completeReport();
            if (NcappCrashDiagnostics.enabled() && report.length() > 16000) {
                report = report.substring(0, 1200)
                        + "\n\n[Informe abreviado en pantalla; copia o comparte el completo]\n\n"
                        + report.substring(report.length() - 13500);
            }
            failureView.setText(report);
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
            if (crashCapture != null) {
                crashCapture.close();
                crashCapture = null;
            }
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
