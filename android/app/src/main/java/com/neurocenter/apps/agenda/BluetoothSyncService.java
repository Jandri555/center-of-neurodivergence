package com.neurocenter.apps.agenda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.neurocenter.MainActivity;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mantiene el servidor RFCOMM fuera del ciclo de vida de MainActivity.
 * Continúa al quitar la aplicación de recientes y solo se detiene
 * explícitamente desde la app o desde la notificación.
 */
public final class BluetoothSyncService extends Service {

    static final String ACTION_START = "com.neurocenter.action.START_BLUETOOTH";
    static final String ACTION_STOP = "com.neurocenter.action.STOP_BLUETOOTH";
    static final String ACTION_STATUS = "com.neurocenter.action.BLUETOOTH_STATUS";
    static final String ACTION_DATA_CHANGED = "com.neurocenter.action.AGENDA_CHANGED";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_ACTIVE = "active";

    private static final String CHANNEL_ID = "agenda_bluetooth_sync";
    private static final int NOTIFICATION_ID = 7104;

    private static volatile boolean running;
    private static volatile String currentStatus = "Bluetooth desconectado.";

    private volatile boolean userRequestedStop;
    private BluetoothSyncServer server;
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private AgendaStore store;

    static boolean isRunning() {
        return running;
    }

    static String currentStatus() {
        return currentStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBluetooth();
            return START_NOT_STICKY;
        }
        if (!running) startBluetooth();
        return running ? START_STICKY : START_NOT_STICKY;
    }

    private void startBluetooth() {
        if (running) return;
        userRequestedStop = false;

        try {
            // Promover el servicio inmediatamente: Android limita el tiempo
            // disponible para llamar a startForeground() tras su arranque.
            enterForeground("Preparando conexión Bluetooth…");

            store = ((AgendaApplication) getApplication()).getAgendaStore();
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) throw new IOException("Este dispositivo no dispone de Bluetooth.");

            server = new BluetoothSyncServer(adapter);
            running = true;
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "agenda-bluetooth-service");
                thread.setDaemon(false);
                return thread;
            });
            publishStatus("Esperando al ordenador. Puedes cerrar la pantalla o quitar Agenda de recientes.", true);
            executor.execute(this::serveBluetooth);
        } catch (Exception ex) {
            running = false;
            publishStatus("No se pudo iniciar Bluetooth: " + messageOf(ex), false);
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void serveBluetooth() {
        String failure = null;
        BluetoothSyncServer currentServer = server;
        try {
            currentServer.serveUntilClosed(
                    store,
                    operations -> {
                        if (operations == null || operations.isEmpty()) return;
                        store.mergeOperations(operations);
                        Log.i("AgendaSync", "Aplicadas " + operations.size()
                                + " operaciones recibidas; entradas actuales: " + store.all().size());
                        // mergeOperations reconstruye y guarda agenda.json antes de avisar a la UI.
                        Intent changed = new Intent(ACTION_DATA_CHANGED).setPackage(getPackageName());
                        sendBroadcast(changed);
                    },
                    () -> {
                        acquireWakeLock();
                        publishStatus("Ordenador conectado. Negociando sincronización…", true);
                    },
                    () -> publishStatus("Conectado por Bluetooth. Sincronización automática bidireccional activa.", true),
                    () -> {
                        releaseWakeLock();
                        publishStatus("Conexión interrumpida. Esperando de nuevo al ordenador…", true);
                    });
        } catch (Exception ex) {
            if (!userRequestedStop) failure = messageOf(ex);
        } finally {
            boolean stoppedByUser = userRequestedStop
                    || (currentServer != null && currentServer.isClosed());
            if (currentServer != null) {
                try {
                    currentServer.close();
                } catch (IOException ignored) {
                    // El socket ya se está cerrando.
                }
            }
            if (server == currentServer) server = null;
            running = false;
            releaseWakeLock();
            if (executor != null) executor.shutdown();
            if (stoppedByUser) {
                publishStatus("Bluetooth desconectado.", false);
            } else {
                publishStatus("Servicio Bluetooth detenido: "
                        + (failure == null ? "la conexión ya no está disponible." : failure), false);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopBluetooth() {
        userRequestedStop = true;
        running = false;
        publishStatus("Bluetooth desconectado.", false);

        BluetoothSyncServer current = server;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // El cierre del servidor desbloquea accept() y la lectura del socket.
            }
        }
        if (executor != null) executor.shutdownNow();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) return;
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "CenterOfNeurodivergence:BluetoothSync");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    private void enterForeground(String status) {
        Notification notification = buildNotification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void publishStatus(String status, boolean active) {
        currentStatus = status;
        if (active) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
        }

        Intent update = new Intent(ACTION_STATUS).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        update.putExtra(EXTRA_ACTIVE, active);
        sendBroadcast(update);
    }

    private Notification buildNotification(String status) {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 1, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopService = new Intent(this, BluetoothSyncService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stopService, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action disconnectAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Desconectar", stopPending).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Agenda · Sincronización Bluetooth")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentIntent(openPending)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(disconnectAction)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Sincronización Bluetooth", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Indica cuándo la agenda mantiene una conexión Bluetooth activa.");
        manager.createNotificationChannel(channel);
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Intencionadamente no detenemos el servicio al quitar la tarea de recientes.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        BluetoothSyncServer current = server;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // El servicio está terminando.
            }
        }
        if (executor != null) executor.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
