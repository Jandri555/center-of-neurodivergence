package com.neurocenter.apps.agenda;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.net.Uri;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.neurocenter.core.NcappManager;
import com.neurocenter.core.NcappPermissionStore;
import com.neurocenter.core.NcappWorkerService;
import com.neurocenter.api.INcappWorker;
import com.neurocenter.api.IJava21Probe;
import com.neurocenter.core.Java21ProbeService;
import com.neurocenter.core.NcappUiController;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.neurocenter.api.NeuroCenterCapabilities;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Primera versión móvil de Agenda. Comparte AgendaStore y el historial de
 * operaciones con la aplicación de escritorio.
 */
public final class MainActivity extends Activity {

    private AgendaStore store;
    private LinearLayout content;
    private TextView pageTitle;
    private boolean calendarView;
    private YearMonth month = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();

    private static final int REQUEST_BT_PERMISSIONS = 7101;
    private static final int REQUEST_ENABLE_BT = 7102;
    private static final int REQUEST_DISCOVERABLE = 7103;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 7105;
    private static final int REQUEST_NCAPP = 7310;
    private static final int DISCOVERABLE_SECONDS = 300;
    private static final String ACTION_USB_PERMISSION =
            "com.neurocenter.action.USB_ACCESSORY_PERMISSION";
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private BluetoothAdapter bluetoothAdapter;
    private boolean bluetoothReceiverRegistered;

    private final BroadcastReceiver bluetoothServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothSyncService.ACTION_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(BluetoothSyncService.EXTRA_STATUS);
                if (status != null && syncStatus != null) syncStatus.setText(status);
                updateBluetoothControls(intent.getBooleanExtra(
                        BluetoothSyncService.EXTRA_ACTIVE, false));
                updateLinkedDevices();
            } else if (BluetoothSyncService.ACTION_DATA_CHANGED.equals(intent.getAction())) {
                refresh();
            }
        }
    };
    private Button bluetoothButton;
    private Button usbButton;
    private TextView syncStatus;
    private TextView linkedDevicesStatus;
    private ParcelFileDescriptor usbAccessoryDescriptor;
    private volatile boolean usbSyncActive;
    private boolean usbReceiverRegistered;
    private boolean usbPermissionPending;
    private final ExecutorService ncappExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            usbPermissionPending = false;
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && accessory != null) {
                openUsbAccessory(accessory);
            } else {
                Toast.makeText(MainActivity.this,
                        "No se ha concedido acceso al accesorio USB.", Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            store = ((AgendaApplication) getApplication()).getAgendaStore();
        } catch (RuntimeException ex) {
            Toast.makeText(this, "No se pudo abrir la agenda: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        registerUsbPermissionReceiver();
        buildScreen();
        refresh();
        updateBluetoothControls(BluetoothSyncService.isRunning());
        handleNcappIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNcappIntent(intent);
    }

    private void handleNcappIntent(Intent intent) {
        if (intent == null) return;
        Uri source = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            source = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            source = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (source == null || !"content".equalsIgnoreCase(source.getScheme())) return;
        String fileName = source.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) fileName = cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Algunos proveedores no ofrecen metadatos; se valida el propio paquete.
        }
        if (fileName == null
                || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".ncapp")) {
            Toast.makeText(this, "Solo se pueden instalar archivos .ncapp.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Uri selected = source;
        new AlertDialog.Builder(this)
                .setTitle("Instalar aplicación NeuroCenter")
                .setMessage("¿Quieres instalar «" + fileName + "» dentro de NeuroCenter?")
                .setPositiveButton("Instalar", (d, w) -> installNcapp(selected))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView heading = new TextView(this);
        heading.setText("Agenda");
        heading.setTextSize(26);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(heading);

        Button javaRuntimeButton = button("Comprobar OpenJDK 21");
        javaRuntimeButton.setOnClickListener(v -> probeJava21Runtime());
        root.addView(javaRuntimeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button externalAppsButton = button("Aplicaciones de NeuroCenter");
        externalAppsButton.setOnClickListener(v -> showExternalApps());
        root.addView(externalAppsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button externalPermissionsButton = button("Permisos de aplicaciones");
        externalPermissionsButton.setOnClickListener(v -> showExternalAppPermissions());
        root.addView(externalPermissionsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pageTitle = new TextView(this);
        pageTitle.setTextSize(14);
        pageTitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(pageTitle);

        LinearLayout views = new LinearLayout(this);
        views.setOrientation(LinearLayout.HORIZONTAL);
        Button pending = button("Pendientes");
        pending.setOnClickListener(v -> {
            calendarView = false;
            refresh();
        });
        Button calendar = button("Calendario");
        calendar.setOnClickListener(v -> {
            calendarView = true;
            refresh();
        });
        views.addView(pending, weight());
        views.addView(calendar, weight());
        root.addView(views);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button newTask = button("+ Tarea");
        newTask.setOnClickListener(v -> showEntryDialog(null, AgendaEntry.TASK));
        Button newEvent = button("+ Evento");
        newEvent.setOnClickListener(v -> showEntryDialog(null, AgendaEntry.EVENT));
        actions.addView(newTask, weight());
        actions.addView(newEvent, weight());
        root.addView(actions);

        Button prepareSync = button("Preparar sincronización");
        prepareSync.setOnClickListener(v -> prepareSync());
        root.addView(prepareSync, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bluetoothButton = button("Esperar conexión Bluetooth");
        bluetoothButton.setOnClickListener(v -> {
            if (BluetoothSyncService.isRunning()) stopBluetoothSync();
            else startBluetoothSync();
        });
        root.addView(bluetoothButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        usbButton = button("Conectar por cable USB");
        usbButton.setOnClickListener(v -> {
            if (usbAccessoryDescriptor != null) stopUsbSync();
            else showUsbInstructions();
        });
        root.addView(usbButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        syncStatus = text(store.isSyncEnabled()
                ? "Historial preparado. Conecta por Bluetooth o por cable USB."
                : "Activa la sincronización en ambos dispositivos para transferir cambios.",
                12, false);
        syncStatus.setPadding(0, dp(6), 0, dp(4));
        root.addView(syncStatus);

        linkedDevicesStatus = text("Equipos vinculados: ninguno", 12, false);
        linkedDevicesStatus.setPadding(0, dp(2), 0, dp(8));
        root.addView(linkedDevicesStatus);
        updateLinkedDevices();

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }


    /** Instala un archivo Java .ncapp dentro de NeuroCenter, nunca como APK Android. */
    private void probeJava21Runtime() {
        Intent intent = new Intent(this, Java21ProbeService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ncappExecutor.execute(() -> {
                    String result;
                    try {
                        result = IJava21Probe.Stub.asInterface(binder).probeVersion();
                    } catch (Exception e) {
                        result = "Error al iniciar OpenJDK 21: " + e;
                    }
                    String message = result;
                    runOnUiThread(() -> {
                        try { unbindService(this); } catch (IllegalArgumentException ignored) {}
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("OpenJDK 21 incorporado")
                                .setMessage(message + "\n\nEsta prueba ejecuta únicamente clases del JRE. "
                                        + "Las aplicaciones .ncapp siguen bloqueadas hasta completar "
                                        + "la ejecución segura en un proceso aislado.")
                                .setPositiveButton("Aceptar", null).show();
                    });
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                Toast.makeText(MainActivity.this, "La JVM de prueba se cerró.", Toast.LENGTH_LONG).show();
            }
        };
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Toast.makeText(this, "No se pudo iniciar el servicio de prueba Java 21.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showExternalApps() {
        final List<NcappManager.InstalledApp> installed;
        try {
            installed = NcappManager.list(this);
        } catch (IOException ex) {
            Toast.makeText(this, "No se pudieron listar las apps: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Aplicaciones de NeuroCenter (.ncapp)")
                .setPositiveButton("Instalar .ncapp", (d, w) -> selectExternalAppPackage())
                .setNegativeButton("Cerrar", null);
        if (installed.isEmpty()) {
            dialog.setMessage("Aún no hay aplicaciones .ncapp instaladas. "
                    + "Las aplicaciones Java 21 se abren en un proceso Android aislado.");
        } else {
            String[] labels = new String[installed.size()];
            for (int i = 0; i < installed.size(); i++) {
                NcappManager.InstalledApp app = installed.get(i);
                labels[i] = app.name() + "  ·  " + app.version();
            }
            dialog.setItems(labels, (d, which) -> {
                NcappManager.InstalledApp app = installed.get(which);
                new AlertDialog.Builder(this)
                        .setTitle(app.name())
                        .setMessage("Archivo Java 21: " + app.id() + ".ncapp\n"
                                + "Clase móvil: " + app.mobileClass()
                                + "\nProceso independiente sin permisos Android del centro.")
                        .setPositiveButton("Abrir", (a, b) -> NcappUiController.open(this, app))
                        .setNeutralButton("Desinstalar", (a, b) -> uninstallNcapp(app))
                        .setNegativeButton("Cerrar", null)
                        .show();
            });
        }
        dialog.show();
    }

    private void uninstallNcapp(NcappManager.InstalledApp app) {
        new AlertDialog.Builder(this)
                .setTitle("Desinstalar " + app.name())
                .setMessage("Se eliminará el paquete .ncapp y se revocarán todos sus permisos.")
                .setPositiveButton("Desinstalar", (d, w) -> {
                    try {
                        NcappManager.uninstall(this, app);
                        NcappPermissionStore.revoke(this, app);
                        Toast.makeText(this, "Aplicación desinstalada", Toast.LENGTH_SHORT).show();
                    } catch (IOException ex) {
                        Toast.makeText(this, "No se pudo desinstalar: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void verifyNcappInSandbox(NcappManager.InstalledApp app) {
        Intent serviceIntent = new Intent(this, NcappWorkerService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                ncappExecutor.execute(() -> {
                    String outcome;
                    try (ParcelFileDescriptor archive = ParcelFileDescriptor.open(
                            app.archive(), ParcelFileDescriptor.MODE_READ_ONLY)) {
                        INcappWorker worker = INcappWorker.Stub.asInterface(binder);
                        if (!worker.isIsolated()) {
                            throw new SecurityException("El proceso NO está aislado.");
                        }
                        String digest = worker.sha256(archive);
                        if (!app.sha256().equals(digest)) {
                            throw new SecurityException("La huella del archivo no coincide.");
                        }
                        outcome = "Comprobado: el proceso aislado puede leer el archivo "
                                + "recibido por Binder. No se ha ejecutado código de la app.";
                    } catch (Exception ex) {
                        outcome = "Error en la comprobación del aislamiento: " + ex.getMessage();
                    }
                    final String message = outcome;
                    runOnUiThread(() -> {
                        try { unbindService(this); } catch (IllegalArgumentException ignored) {}
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                });
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                Toast.makeText(MainActivity.this, "El proceso aislado se ha cerrado.", Toast.LENGTH_SHORT).show();
            }
        };
        try {
            boolean started = bindIsolatedService(serviceIntent, Context.BIND_AUTO_CREATE,
                    "ncapp_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    getMainExecutor(), connection);
            if (!started) {
                Toast.makeText(this, "No se pudo iniciar el servicio aislado.", Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException ex) {
            Toast.makeText(this, "No se pudo crear el proceso aislado: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showExternalAppPermissions() {
        final List<NcappManager.InstalledApp> installed;
        try {
            installed = NcappManager.list(this);
        } catch (IOException ex) {
            Toast.makeText(this, "Error al consultar las apps: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (installed.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Permisos de aplicaciones")
                    .setMessage("No hay aplicaciones .ncapp instaladas.")
                    .setPositiveButton("Aceptar", null).show();
            return;
        }
        String[] labels = new String[installed.size()];
        for (int i = 0; i < installed.size(); i++) {
            labels[i] = installed.get(i).name() + " (" + installed.get(i).id() + ")";
        }
        new AlertDialog.Builder(this).setTitle("Elige una aplicación")
                .setItems(labels, (dialog, which) -> editExternalAppPermissions(installed.get(which)))
                .setNegativeButton("Cancelar", null).show();
    }

    private void editExternalAppPermissions(NcappManager.InstalledApp app) {
        final java.util.Set<String> current = NcappPermissionStore.granted(this, app);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(18), dp(8), dp(18), dp(8));
        java.util.Map<String, CheckBox> controls = new java.util.LinkedHashMap<>();
        addCapabilityOption(options, controls, NeuroCenterCapabilities.AGENDA_READ,
                "Leer la Agenda", "Consultar tareas y eventos de NeuroCenter.", current);
        addCapabilityOption(options, controls, NeuroCenterCapabilities.AGENDA_WRITE,
                "Modificar la Agenda", "Crear o eliminar tareas y eventos.", current);
        addCapabilityOption(options, controls, NeuroCenterCapabilities.CREDENTIALS_ACCESS,
                "Credenciales", "Acceder únicamente a las credenciales propias de la app.", current);
        addCapabilityOption(options, controls, NeuroCenterCapabilities.NETWORK_CONNECT,
                "Conexiones de red", "Solicitar conexiones a través de NeuroCenter.", current);
        addCapabilityOption(options, controls, NeuroCenterCapabilities.FILES_READ_DOCUMENTS,
                "Leer documentos", "Solicitar lectura de documentos seleccionados.", current);
        addCapabilityOption(options, controls, NeuroCenterCapabilities.FILES_WRITE_DOCUMENTS,
                "Modificar documentos", "Solicitar escritura de documentos seleccionados.", current);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Permisos · " + app.name()).setView(scroll)
                .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    java.util.Set<String> updated = new java.util.HashSet<>();
                    controls.forEach((id, control) -> { if (control.isChecked()) updated.add(id); });
                    try {
                        NcappPermissionStore.setGranted(this, app, updated);
                        dialog.dismiss();
                        Toast.makeText(this, "Preferencias guardadas. El proceso Java"
                                + " sigue aislado; aún no hay puente de servicios privilegiados.",
                                Toast.LENGTH_LONG).show();
                    } catch (RuntimeException ex) {
                        Toast.makeText(this, "No se guardaron los permisos: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void addCapabilityOption(LinearLayout parent, java.util.Map<String, CheckBox> controls,
                                     String id, String label, String description,
                                     java.util.Set<String> current) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setChecked(current.contains(id));
        controls.put(id, checkBox);
        TextView detail = text(description, 12, false);
        detail.setPadding(dp(34), 0, 0, dp(10));
        parent.addView(checkBox);
        parent.addView(detail);
    }

    private void selectExternalAppPackage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_NCAPP);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "No se pudo elegir el .ncapp: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void installNcapp(Uri source) {
        ncappExecutor.execute(() -> {
            String result;
            try {
                NcappManager.InstalledApp app = NcappManager.install(this, source);
                result = "Instalada " + app.name() + " (" + app.id() + "). "
                        + "Puedes abrirla desde Aplicaciones de NeuroCenter.";
            } catch (Exception ex) {
                result = "No se pudo instalar el .ncapp: " + ex.getMessage();
            }
            String message = result;
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        });
    }

    private void refresh() {
        if (content == null || store == null) return;
        updateLinkedDevices();
        content.removeAllViews();

        if (calendarView) {
            pageTitle.setText(month.getMonth().getDisplayName(
                    java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es-ES"))
                    + " de " + month.getYear());
            content.addView(buildCalendar());
            TextView selectedHeading = text(
                    selectedDate.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", java.util.Locale.forLanguageTag("es-ES"))),
                    18, true);
            selectedHeading.setPadding(0, dp(18), 0, dp(10));
            content.addView(selectedHeading);
            List<AgendaEntry> dayEntries = new ArrayList<>(store.on(selectedDate));
            addEntries(dayEntries, "No hay nada planeado para este día.");
        } else {
            List<AgendaEntry> pending = new ArrayList<>(store.pendingTasks());
            pageTitle.setText(pending.size() + (pending.size() == 1 ? " tarea pendiente" : " tareas pendientes"));
            addEntries(pending, "No tienes tareas pendientes.");
        }
    }

    private View buildCalendar() {
        LinearLayout calendarRoot = new LinearLayout(this);
        calendarRoot.setOrientation(LinearLayout.VERTICAL);

        LinearLayout monthNav = new LinearLayout(this);
        Button previous = button("‹");
        previous.setOnClickListener(v -> {
            month = month.minusMonths(1);
            refresh();
        });
        Button next = button("›");
        next.setOnClickListener(v -> {
            month = month.plusMonths(1);
            refresh();
        });
        Button today = button("Hoy");
        today.setOnClickListener(v -> {
            selectedDate = LocalDate.now();
            month = YearMonth.from(selectedDate);
            refresh();
        });
        monthNav.addView(previous, weight());
        monthNav.addView(today, weight());
        monthNav.addView(next, weight());
        calendarRoot.addView(monthNav);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        String[] weekdays = {"L", "M", "X", "J", "V", "S", "D"};
        for (String day : weekdays) {
            TextView label = text(day, 13, true);
            label.setGravity(Gravity.CENTER);
            grid.addView(label, gridCell());
        }

        LocalDate first = month.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int days = month.lengthOfMonth();
        int total = 42;
        for (int cell = 0; cell < total; cell++) {
            int dayNumber = cell - offset + 1;
            if (dayNumber < 1 || dayNumber > days) {
                TextView empty = text(" ", 14, false);
                grid.addView(empty, gridCell());
                continue;
            }

            LocalDate date = month.atDay(dayNumber);
            boolean hasEntries = !store.on(date).isEmpty();
            String label = String.valueOf(dayNumber) + (hasEntries ? " ·" : "");
            Button day = button(label);
            day.setAllCaps(false);
            day.setMinHeight(dp(42));
            if (date.equals(selectedDate)) day.setTypeface(null, android.graphics.Typeface.BOLD);
            day.setOnClickListener(v -> {
                selectedDate = date;
                refresh();
            });
            grid.addView(day, gridCell());
        }

        calendarRoot.addView(grid);
        return calendarRoot;
    }

    private void addEntries(List<AgendaEntry> entries, String emptyMessage) {
        entries.sort(Comparator
                .comparing((AgendaEntry entry) -> entry.date == null ? "" : entry.date)
                .thenComparing(entry -> entry.time == null ? "" : entry.time)
                .thenComparing(entry -> entry.title == null ? "" : entry.title.toLowerCase()));

        if (entries.isEmpty()) {
            TextView empty = text(emptyMessage, 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(32), 0, dp(32));
            content.addView(empty);
            return;
        }
        for (AgendaEntry entry : entries) content.addView(buildEntry(entry));
    }

    private View buildEntry(AgendaEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(0xFFF0F1F4);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        String kind = entry.isEvent() ? "EVENTO" : "TAREA";
        TextView kindLabel = text(kind + " · " + safe(entry.date)
                + (entry.time == null || entry.time.trim().isEmpty() ? "" : " · " + entry.time), 12, true);
        card.addView(kindLabel);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        if (!entry.isEvent()) {
            CheckBox done = new CheckBox(this);
            done.setChecked(entry.done);
            done.setOnCheckedChangeListener((button, checked) -> {
                synchronized (store) {
                    entry.done = checked;
                    save();
                }
                refresh();
            });
            main.addView(done);
        }

        TextView title = text(safe(entry.title), 17, true);
        if (entry.done) title.setPaintFlags(title.getPaintFlags()
                | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        main.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(main);

        if (entry.notes != null && !entry.notes.trim().isEmpty()) {
            TextView notes = text(entry.notes, 13, false);
            notes.setPadding(0, dp(4), 0, 0);
            card.addView(notes);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = button("Editar");
        edit.setOnClickListener(v -> showEntryDialog(entry, entry.type));
        Button delete = button("Borrar");
        delete.setOnClickListener(v -> confirmDelete(entry));
        buttons.addView(edit, weight());
        buttons.addView(delete, weight());
        card.addView(buttons);
        return card;
    }

    private void showEntryDialog(AgendaEntry existing, String type) {
        boolean event = AgendaEntry.EVENT.equals(type);
        AgendaEntry candidate = existing == null ? new AgendaEntry() : existing.copy();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText title = field("Título", candidate.title, InputType.TYPE_CLASS_TEXT);
        EditText date = field("Fecha (AAAA-MM-DD)",
                candidate.date == null ? LocalDate.now().toString() : candidate.date,
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText time = field(event ? "Hora (HH:mm)" : "Hora límite (opcional)",
                candidate.time == null ? "" : candidate.time,
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        EditText notes = field("Notas", candidate.notes,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(title);
        form.addView(date);
        form.addView(time);
        if (!event) form.addView(notes);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? (event ? "Nuevo evento" : "Nueva tarea")
                        : (event ? "Editar evento" : "Editar tarea"))
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String cleanTitle = title.getText().toString().trim();
                if (cleanTitle.isEmpty()) throw new IllegalArgumentException("Escribe un título.");
                LocalDate parsedDate = LocalDate.parse(date.getText().toString().trim());
                String timeText = time.getText().toString().trim();
                LocalTime parsedTime = timeText.isEmpty() ? null : LocalTime.parse(timeText);
                if (event && parsedTime == null) throw new IllegalArgumentException("Los eventos necesitan una hora.");

                candidate.type = type;
                candidate.title = cleanTitle;
                candidate.date = parsedDate.toString();
                candidate.time = parsedTime == null ? null : parsedTime.toString();

                if (event) {
                    if (existing == null) {
                        candidate.remindDayBefore = true;
                        candidate.remindCustom = false;
                        candidate.remindBeforeMinutes = 30;
                    }
                    candidate.notes = "";
                    candidate.done = false;
                    candidate.remind = false;
                    candidate.notified = false;
                    candidate.notifiedDayBefore = false;
                    candidate.notifiedCustom = false;
                } else {
                    candidate.notes = notes.getText().toString().trim();
                    candidate.remind = false;
                    candidate.notified = false;
                    candidate.remindDayBefore = false;
                    candidate.notifiedDayBefore = false;
                    candidate.remindCustom = false;
                    candidate.notifiedCustom = false;
                }

                synchronized (store) {
                    if (existing == null) {
                        store.add(candidate);
                        selectedDate = parsedDate;
                    } else {
                        store.replace(existing, candidate);
                        selectedDate = parsedDate;
                    }
                    month = YearMonth.from(selectedDate);
                    save();
                }
                dialog.dismiss();
                refresh();
            } catch (RuntimeException ex) {
                Toast.makeText(this, ex.getMessage() == null ? "Datos no válidos." : ex.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private void confirmDelete(AgendaEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar entrada")
                .setMessage("¿Quieres borrar «" + safe(entry.title) + "»?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (dialog, which) -> {
                    synchronized (store) {
                        store.remove(entry);
                        save();
                    }
                    refresh();
                })
                .show();
    }

    private void prepareSync() {
        if (store.isSyncEnabled()) {
            Toast.makeText(this, "El historial de sincronización ya está activo.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Preparar sincronización")
                .setMessage("Se registrarán las altas, modificaciones y borrados para poder intercambiarlos "
                        + "con el ordenador. Bluetooth ya tiene un modo de conexión; la conexión por cable USB todavía está en desarrollo. ¿Activar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Activar", (dialog, which) -> {
                    try {
                        store.enableSync();
                        Toast.makeText(this, "Historial de sincronización activado.", Toast.LENGTH_LONG).show();
                    } catch (RuntimeException ex) {
                        Toast.makeText(this, "No se pudo activar: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        usbReceiverRegistered = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothSyncService.ACTION_STATUS);
        filter.addAction(BluetoothSyncService.ACTION_DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothServiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothServiceReceiver, filter);
        }
        bluetoothReceiverRegistered = true;
        updateBluetoothControls(BluetoothSyncService.isRunning());
        if (BluetoothSyncService.isRunning() && syncStatus != null) {
            syncStatus.setText(BluetoothSyncService.currentStatus());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // El servicio Bluetooth puede haber aplicado operaciones mientras la
        // Activity no estaba visible y su BroadcastReceiver no estaba registrado.
        // Recarga siempre la agenda persistida al volver a primer plano.
        refresh();
        updateBluetoothControls(BluetoothSyncService.isRunning());
        inspectUsbAccessory();
    }

    @Override
    protected void onStop() {
        if (bluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothServiceReceiver);
            bluetoothReceiverRegistered = false;
        }
        super.onStop();
    }

    private void updateBluetoothControls(boolean active) {
        if (bluetoothButton == null) return;
        bluetoothButton.setText(active ? "Desconectar Bluetooth" : "Esperar conexión Bluetooth");
        bluetoothButton.setEnabled(!usbSyncActive);
        if (usbButton != null) usbButton.setEnabled(!active && !usbSyncActive);
        updateLinkedDevices();
    }

    private void updateLinkedDevices() {
        if (linkedDevicesStatus == null || store == null) return;
        List<AgendaSyncJournal.PeerInfo> peers = store.linkedPeers();
        if (peers.isEmpty()) {
            linkedDevicesStatus.setText("Equipos vinculados: ninguno todavía.");
            return;
        }

        StringBuilder status = new StringBuilder("Equipos vinculados (")
                .append(peers.size()).append("):");
        for (AgendaSyncJournal.PeerInfo peer : peers) {
            int pending = peer.pendingOperations;
            status.append("\n• ")
                    .append(safe(peer.deviceName))
                    .append(pending == 0
                            ? " — al día"
                            : " — " + pending + (pending == 1
                                    ? " operación pendiente"
                                    : " operaciones pendientes"));
        }
        linkedDevicesStatus.setText(status.toString());
    }

    private void showUsbInstructions() {
        if (!store.isSyncEnabled()) {
            Toast.makeText(this, "Activa primero «Preparar sincronización» en ambos dispositivos.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sincronización por cable USB")
                .setMessage("Conecta el móvil al ordenador con un cable de datos. En el ordenador, "
                        + "abre la Agenda y pulsa «Sincronizar USB».\n\n"
                        + "El ordenador cambiará el teléfono al modo Android Open Accessory. "
                        + "Cuando Android pregunte qué aplicación debe gestionar el accesorio, "
                        + "selecciona Agenda. Mantén esta pantalla abierta hasta que termine.")
                .setPositiveButton("Entendido", null)
                .show();
        inspectUsbAccessory();
    }

    private void inspectUsbAccessory() {
        if (isFinishing() || usbSyncActive || usbPermissionPending || usbAccessoryDescriptor != null) return;
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) return;
        UsbAccessory[] accessories = manager.getAccessoryList();
        if (accessories == null || accessories.length == 0) return;

        UsbAccessory accessory = accessories[0];
        if (manager.hasPermission(accessory)) {
            openUsbAccessory(accessory);
            return;
        }

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, permissionIntent, flags);
        usbPermissionPending = true;
        manager.requestPermission(accessory, pendingIntent);
        syncStatus.setText("Esperando autorización para acceder al accesorio USB…");
    }

    private void openUsbAccessory(UsbAccessory accessory) {
        if (usbSyncActive || usbAccessoryDescriptor != null) return;
        if (BluetoothSyncService.isRunning()) {
            Toast.makeText(this, "Cierra primero la sesión Bluetooth para usar USB.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!store.isSyncEnabled()) {
            Toast.makeText(this, "Activa primero el historial de sincronización.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "El servicio USB no está disponible.", Toast.LENGTH_LONG).show();
            return;
        }

        ParcelFileDescriptor descriptor = manager.openAccessory(accessory);
        if (descriptor == null) {
            Toast.makeText(this, "No se pudo abrir el accesorio USB.", Toast.LENGTH_LONG).show();
            return;
        }

        usbAccessoryDescriptor = descriptor;
        usbSyncActive = true;
        bluetoothButton.setEnabled(false);
        usbButton.setEnabled(true);
        usbButton.setText("Desconectar USB");
        syncStatus.setText("Conectado por USB. Los cambios se sincronizan automáticamente.");

        syncExecutor.execute(() -> {
            String failure = null;
            try {
                FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                AgendaSyncProtocol.runServerSession(
                        input, output, store, this::mergeOnMainThread,
                        () -> usbSyncActive,
                        () -> runOnUiThread(() ->
                                syncStatus.setText("Conectado por USB. Los cambios se sincronizan automáticamente.")));
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // El descriptor se cierra aunque se pierda el cable.
                }
                if (usbAccessoryDescriptor == descriptor) usbAccessoryDescriptor = null;
                boolean stoppedByUser = !usbSyncActive;
                usbSyncActive = false;
                final String error = stoppedByUser ? null : failure;
                runOnUiThread(() -> {
                    if (usbButton != null) {
                        usbButton.setEnabled(true);
                        usbButton.setText("Conectar por cable USB");
                    }
                    if (bluetoothButton != null) bluetoothButton.setEnabled(true);
                    if (error == null) {
                        syncStatus.setText("Conexión USB cerrada.");
                        refresh();
                    } else {
                        syncStatus.setText("No se pudo completar la sincronización USB.");
                        Toast.makeText(this, "Error de sincronización USB: " + error,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void startBluetoothSync() {
        if (usbSyncActive || usbAccessoryDescriptor != null) {
            Toast.makeText(this, "Cierra primero la sesión USB para usar Bluetooth.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!store.isSyncEnabled()) {
            Toast.makeText(this,
                    "Primero activa «Preparar sincronización» en ambos dispositivos.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
            return;
        }
        continueBluetoothStart();
    }

    private void continueBluetoothStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] required = {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
            List<String> missing = new ArrayList<>();
            for (String permission : required) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQUEST_BT_PERMISSIONS);
                return;
            }
        }
        requestBluetoothDiscoverability();
    }

    private void requestBluetoothDiscoverability() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
                return;
            }
            Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS);
            startActivityForResult(discoverable, REQUEST_DISCOVERABLE);
        } catch (SecurityException ex) {
            Toast.makeText(this, "Falta permiso para usar Bluetooth: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "La sincronización seguirá funcionando, pero Android puede ocultar su notificación. "
                                + "Podrás desconectar desde Agenda.",
                        Toast.LENGTH_LONG).show();
            }
            continueBluetoothStart();
            return;
        }
        if (requestCode != REQUEST_BT_PERMISSIONS) return;

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) requestBluetoothDiscoverability();
        else Toast.makeText(this, "Necesito permiso para anunciar y aceptar la conexión Bluetooth.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) requestBluetoothDiscoverability();
            else Toast.makeText(this, "Bluetooth no se ha activado.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode > 0) startBluetoothServer();
            else Toast.makeText(this, "No se ha permitido que el móvil sea visible.", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQUEST_NCAPP && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            installNcapp(data.getData());
        }
    }

    private void startBluetoothServer() {
        Intent serviceIntent = new Intent(this, BluetoothSyncService.class)
                .setAction(BluetoothSyncService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            updateBluetoothControls(true);
            syncStatus.setText("Iniciando el servicio Bluetooth…");
        } catch (RuntimeException ex) {
            Toast.makeText(this, "No se pudo iniciar el servicio Bluetooth: "
                    + ex.getMessage(), Toast.LENGTH_LONG).show();
            updateBluetoothControls(false);
        }
    }

    private void stopBluetoothSync() {
        Intent serviceIntent = new Intent(this, BluetoothSyncService.class)
                .setAction(BluetoothSyncService.ACTION_STOP);
        startService(serviceIntent);
    }

    private void stopUsbSync() {
        ParcelFileDescriptor descriptor = usbAccessoryDescriptor;
        if (descriptor == null) return;
        usbSyncActive = false;
        try {
            descriptor.close();
        } catch (IOException ex) {
            Toast.makeText(this, "No se pudo cerrar USB: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** El protocolo se ejecuta en segundo plano, pero la lista se modifica en el hilo de UI. */
    private void mergeOnMainThread(List<AgendaOperation> operations) throws IOException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runOnUiThread(() -> {
            try {
                store.mergeOperations(operations);
                refresh();
            } catch (RuntimeException ex) {
                failure.set(ex);
            } finally {
                finished.countDown();
            }
        });

        try {
            if (!finished.await(30, TimeUnit.SECONDS)) {
                throw new IOException("La interfaz tardó demasiado en aplicar los cambios recibidos.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpió la aplicación de los cambios recibidos.", ex);
        }

        if (failure.get() != null) {
            throw new IOException("No se pudo aplicar el historial recibido.", failure.get());
        }
    }

    @Override
    protected void onDestroy() {
        usbSyncActive = false;
        ParcelFileDescriptor descriptor = usbAccessoryDescriptor;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // La actividad está finalizando.
            }
        }
        if (usbReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver);
            usbReceiverRegistered = false;
        }
        syncExecutor.shutdownNow();
        ncappExecutor.shutdownNow();
        NcappUiController.closeAll(this);
        super.onDestroy();
    }

    private void save() {
        try {
            store.save();
        } catch (UncheckedIOException ex) {
            Toast.makeText(this, "No se pudo guardar la agenda: " + ex.getCause().getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private EditText field(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        field.setInputType(inputType);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        return field;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GridLayout.LayoutParams gridCell() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(44);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(1), dp(1), dp(1), dp(1));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
