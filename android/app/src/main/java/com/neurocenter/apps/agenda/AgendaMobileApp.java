package com.neurocenter.apps.agenda;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
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

import com.neurocenter.api.NeuroMobileApp;
import com.neurocenter.api.NeuroMobileContext;
import com.neurocenter.core.ThemeManager;
import com.neurocenter.core.ThemeManager.Palette;
import com.neurocenter.ui.Ui;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agenda como app integrada del panel móvil (equivale a AgendaApp + AgendaPanel de escritorio).
 *
 * Comparte AgendaStore y el protocolo de sincronización con el escritorio. Las partes que antes
 * pertenecían a MainActivity (receptores de difusión, permisos Bluetooth, accesorio USB) se enlazan
 * ahora al ciclo de vida que ofrece el host: onStart/onStop en el hilo de trabajo de la app y
 * onResume/onPause/onActivityResult en el hilo principal.
 */
public final class AgendaMobileApp implements NeuroMobileApp {

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final int OVERDUE = 0xFFDB4B4B;

    private static final int REQUEST_BT_PERMISSIONS = 7101;
    private static final int REQUEST_ENABLE_BT = 7102;
    private static final int REQUEST_DISCOVERABLE = 7103;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 7105;
    private static final int DISCOVERABLE_SECONDS = 300;
    private static final String ACTION_USB_PERMISSION =
            "com.neurocenter.action.USB_ACCESSORY_PERMISSION";

    // ---- estado de la app (sobrevive a createView repetidos)
    private NeuroMobileContext ctx;
    private Activity activity;
    private AgendaStore store;
    private boolean calendarView;
    private YearMonth month = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private boolean syncPanelOpen;

    // ---- vistas (se reasignan en cada createView)
    private Palette palette = ThemeManager.palette();
    private LinearLayout content;
    private TextView pageTitle;
    private Button bluetoothButton;
    private Button usbButton;
    private TextView syncStatus;
    private TextView linkedDevicesStatus;

    // ---- sincronización
    private ExecutorService syncExecutor;
    private BluetoothAdapter bluetoothAdapter;
    private ParcelFileDescriptor usbAccessoryDescriptor;
    private volatile boolean usbSyncActive;
    private boolean bluetoothReceiverRegistered;
    private boolean usbReceiverRegistered;
    private boolean usbPermissionPending;
    private volatile boolean visible;

    private final BroadcastReceiver bluetoothServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothSyncService.ACTION_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(BluetoothSyncService.EXTRA_STATUS);
                if (status != null && syncStatus != null) syncStatus.setText(status);
                updateBluetoothControls(intent.getBooleanExtra(BluetoothSyncService.EXTRA_ACTIVE, false));
                updateLinkedDevices();
            } else if (BluetoothSyncService.ACTION_DATA_CHANGED.equals(intent.getAction())) {
                refresh();
            }
        }
    };

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            usbPermissionPending = false;
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && accessory != null) {
                openUsbAccessory(accessory);
            } else {
                toast("No se ha concedido acceso al accesorio USB.");
            }
        }
    };

    // ------------------------------------------------------------------ identidad

    @Override public String id() { return "agenda"; }

    @Override public String name() { return "Agenda"; }

    @Override public String description() {
        return "Calendario, tareas y eventos, con sincronización con el escritorio por Bluetooth y USB.";
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Hilo de trabajo de la app: abre (o carga) el almacén y registra el receptor USB. */
    @Override
    public void onStart(NeuroMobileContext context) {
        this.ctx = context;
        this.activity = context.activity();
        this.store = ((AgendaApplication) activity.getApplication()).getAgendaStore();
        this.syncExecutor = Executors.newSingleThreadExecutor();
        registerUsbPermissionReceiver();
    }

    @Override
    public View createView(NeuroMobileContext context, LayoutInflater inflater, ViewGroup parent) {
        palette = ThemeManager.palette();
        View root = buildScreen();
        refresh();
        updateBluetoothControls(BluetoothSyncService.isRunning());
        return root;
    }

    @Override
    public void onResume() {
        visible = true;
        registerBluetoothReceiver();
        // El servicio Bluetooth puede haber aplicado operaciones mientras la app no estaba
        // visible y su receptor no estaba registrado: se recarga siempre la agenda persistida.
        refresh();
        updateBluetoothControls(BluetoothSyncService.isRunning());
        if (BluetoothSyncService.isRunning() && syncStatus != null) {
            syncStatus.setText(BluetoothSyncService.currentStatus());
        }
        inspectUsbAccessory();
    }

    @Override
    public void onPause() {
        visible = false;
        if (bluetoothReceiverRegistered) {
            try {
                activity.unregisterReceiver(bluetoothServiceReceiver);
            } catch (IllegalArgumentException ignored) {
                // ya estaba desregistrado
            }
            bluetoothReceiverRegistered = false;
        }
    }

    /** Hilo de trabajo de la app: cierra USB y libera receptores. Bluetooth sigue en su servicio. */
    @Override
    public void onStop() {
        usbSyncActive = false;
        ParcelFileDescriptor descriptor = usbAccessoryDescriptor;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // la app está cerrándose
            }
        }
        if (usbReceiverRegistered) {
            try {
                activity.unregisterReceiver(usbPermissionReceiver);
            } catch (IllegalArgumentException ignored) {
                // ya estaba desregistrado
            }
            usbReceiverRegistered = false;
        }
        if (syncExecutor != null) syncExecutor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                toast("La sincronización seguirá funcionando, pero Android puede ocultar su notificación. "
                        + "Podrás desconectar desde Agenda.");
            }
            continueBluetoothStart();
            return;
        }
        if (requestCode != REQUEST_BT_PERMISSIONS) return;

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) requestBluetoothDiscoverability();
        else toast("Necesito permiso para anunciar y aceptar la conexión Bluetooth.");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) requestBluetoothDiscoverability();
            else toast("Bluetooth no se ha activado.");
            return;
        }
        if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode > 0) startBluetoothServer();
            else toast("No se ha permitido que el móvil sea visible.");
        }
    }

    // ------------------------------------------------------------------ interfaz

    private View buildScreen() {
        Palette p = palette;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));

        pageTitle = Ui.text(activity, "", 14, false, p.muted);
        pageTitle.setPadding(0, 0, 0, dp(10));
        root.addView(pageTitle);

        LinearLayout views = row();
        Button pending = Ui.button(activity, "Pendientes", p);
        pending.setOnClickListener(v -> {
            calendarView = false;
            refresh();
        });
        Button calendar = Ui.button(activity, "Calendario", p);
        calendar.setOnClickListener(v -> {
            calendarView = true;
            refresh();
        });
        views.addView(pending, weight());
        views.addView(calendar, weight());
        root.addView(views);

        LinearLayout actions = row();
        Button newTask = Ui.primaryButton(activity, "+ Tarea", p);
        newTask.setOnClickListener(v -> showEntryDialog(null, AgendaEntry.TASK));
        Button newEvent = Ui.primaryButton(activity, "+ Evento", p);
        newEvent.setOnClickListener(v -> showEntryDialog(null, AgendaEntry.EVENT));
        actions.addView(newTask, weight());
        actions.addView(newEvent, weight());
        root.addView(actions);

        root.addView(buildSyncCard());

        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** Sincronización plegable: en móvil los controles ocupaban media pantalla. */
    private View buildSyncCard() {
        Palette p = palette;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.setBackground(Ui.rounded(p.surface, dp(12), p.border, 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(activity, "Sincronización", 15, true, p.text);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button toggle = Ui.button(activity, syncPanelOpen ? "Ocultar" : "Mostrar", p);
        header.addView(toggle);
        card.addView(header);

        syncStatus = Ui.text(activity, store != null && store.isSyncEnabled()
                ? "Historial preparado. Conecta por Bluetooth o por cable USB."
                : "Activa la sincronización en ambos dispositivos para transferir cambios.",
                12, false, p.muted);
        syncStatus.setPadding(0, dp(4), 0, dp(4));
        card.addView(syncStatus);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(syncPanelOpen ? View.VISIBLE : View.GONE);

        Button prepareSync = Ui.button(activity, "Preparar sincronización", p);
        prepareSync.setOnClickListener(v -> prepareSync());
        body.addView(prepareSync, matchWidth());

        bluetoothButton = Ui.button(activity, "Esperar conexión Bluetooth", p);
        bluetoothButton.setOnClickListener(v -> {
            if (BluetoothSyncService.isRunning()) stopBluetoothSync();
            else startBluetoothSync();
        });
        body.addView(bluetoothButton, matchWidth());

        usbButton = Ui.button(activity, "Conectar por cable USB", p);
        usbButton.setOnClickListener(v -> {
            if (usbAccessoryDescriptor != null) stopUsbSync();
            else showUsbInstructions();
        });
        body.addView(usbButton, matchWidth());

        linkedDevicesStatus = Ui.text(activity, "Equipos vinculados: ninguno", 12, false, p.muted);
        linkedDevicesStatus.setPadding(0, dp(6), 0, 0);
        body.addView(linkedDevicesStatus);
        card.addView(body);

        toggle.setOnClickListener(v -> {
            syncPanelOpen = !syncPanelOpen;
            body.setVisibility(syncPanelOpen ? View.VISIBLE : View.GONE);
            toggle.setText(syncPanelOpen ? "Ocultar" : "Mostrar");
        });
        updateLinkedDevices();
        return card;
    }

    private void refresh() {
        if (content == null || store == null || pageTitle == null) return;
        updateLinkedDevices();
        content.removeAllViews();

        if (calendarView) {
            pageTitle.setText(capitalize(month.getMonth().getDisplayName(TextStyle.FULL, ES))
                    + " de " + month.getYear());
            content.addView(buildCalendar());
            TextView selectedHeading = Ui.text(activity,
                    capitalize(selectedDate.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES))),
                    18, true, palette.text);
            selectedHeading.setPadding(0, dp(18), 0, dp(10));
            content.addView(selectedHeading);
            List<AgendaEntry> dayEntries = new ArrayList<>(store.on(selectedDate));
            addEntries(dayEntries, "No hay nada planeado para este día.");
        } else {
            List<AgendaEntry> pending = new ArrayList<>(store.pendingTasks());
            LocalDateTime now = LocalDateTime.now();
            long overdue = pending.stream().filter(e -> e.isOverdue(now)).count();
            pageTitle.setText(pending.size() + (pending.size() == 1 ? " tarea pendiente" : " tareas pendientes")
                    + (overdue > 0 ? ", " + overdue + (overdue == 1 ? " vencida" : " vencidas") : ""));
            pageTitle.setTextColor(overdue > 0 ? OVERDUE : palette.muted);
            addEntries(pending, "No tienes tareas pendientes.");
        }
        if (calendarView) pageTitle.setTextColor(palette.muted);
    }

    private View buildCalendar() {
        Palette p = palette;
        LinearLayout calendarRoot = new LinearLayout(activity);
        calendarRoot.setOrientation(LinearLayout.VERTICAL);

        LinearLayout monthNav = row();
        Button previous = Ui.button(activity, "‹", p);
        previous.setOnClickListener(v -> {
            month = month.minusMonths(1);
            refresh();
        });
        Button next = Ui.button(activity, "›", p);
        next.setOnClickListener(v -> {
            month = month.plusMonths(1);
            refresh();
        });
        Button today = Ui.button(activity, "Hoy", p);
        today.setOnClickListener(v -> {
            selectedDate = LocalDate.now();
            month = YearMonth.from(selectedDate);
            refresh();
        });
        monthNav.addView(previous, weight());
        monthNav.addView(today, weight());
        monthNav.addView(next, weight());
        calendarRoot.addView(monthNav);

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(7);
        String[] weekdays = {"L", "M", "X", "J", "V", "S", "D"};
        for (String day : weekdays) {
            TextView label = Ui.text(activity, day, 13, true, p.muted);
            label.setGravity(Gravity.CENTER);
            grid.addView(label, gridCell(dp(28)));
        }

        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        int days = month.lengthOfMonth();
        LocalDate todayDate = LocalDate.now();
        for (int cell = 0; cell < 42; cell++) {
            int dayNumber = cell - offset + 1;
            if (dayNumber < 1 || dayNumber > days) {
                grid.addView(Ui.text(activity, " ", 14, false, p.text), gridCell(dp(46)));
                continue;
            }
            LocalDate date = month.atDay(dayNumber);
            boolean hasEntries = !store.on(date).isEmpty();
            grid.addView(dayCell(date, date.equals(selectedDate), date.equals(todayDate), hasEntries),
                    gridCell(dp(46)));
        }
        calendarRoot.addView(grid);
        return calendarRoot;
    }

    private TextView dayCell(LocalDate date, boolean isSelected, boolean isToday, boolean hasEntries) {
        Palette p = palette;
        TextView cell = new TextView(activity);
        cell.setText(date.getDayOfMonth() + "\n" + (hasEntries ? "•" : " "));
        cell.setTextSize(14);
        cell.setGravity(Gravity.CENTER);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setTypeface(null, isToday || isSelected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (isSelected) {
            cell.setTextColor(Color.WHITE);
            cell.setBackground(Ui.ripple(p.primary, Ui.withAlpha(Color.WHITE, 0x40), dp(10), p.primary, 0));
        } else {
            cell.setTextColor(isToday ? p.primary : p.text);
            cell.setBackground(Ui.ripple(Color.TRANSPARENT, Ui.withAlpha(p.text, 0x28),
                    dp(10), isToday ? p.primary : Color.TRANSPARENT, isToday ? 2 : 0));
        }
        cell.setOnClickListener(v -> {
            selectedDate = date;
            refresh();
        });
        return cell;
    }

    private void addEntries(List<AgendaEntry> entries, String emptyMessage) {
        entries.sort(Comparator
                .comparing((AgendaEntry entry) -> entry.date == null ? "" : entry.date)
                .thenComparing(entry -> entry.time == null ? "" : entry.time)
                .thenComparing(entry -> entry.title == null ? "" : entry.title.toLowerCase()));

        if (entries.isEmpty()) {
            TextView empty = Ui.text(activity, emptyMessage, 15, false, palette.muted);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(32), 0, dp(32));
            content.addView(empty);
            return;
        }
        for (AgendaEntry entry : entries) content.addView(buildEntry(entry));
    }

    private View buildEntry(AgendaEntry entry) {
        Palette p = palette;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(Ui.rounded(p.surface, dp(12), p.border, 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        boolean overdue = entry.isOverdue(LocalDateTime.now());
        String kind = entry.isEvent() ? "EVENTO" : "TAREA";
        TextView kindLabel = Ui.text(activity, kind + " · " + safe(entry.date)
                        + (entry.time == null || entry.time.trim().isEmpty() ? "" : " · " + entry.time)
                        + (overdue ? " · VENCIDA" : ""),
                12, true, overdue ? OVERDUE : p.muted);
        card.addView(kindLabel);

        LinearLayout main = new LinearLayout(activity);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        if (!entry.isEvent()) {
            CheckBox done = new CheckBox(activity);
            Ui.styleCheckBox(done, p);
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

        TextView title = Ui.text(activity, safe(entry.title), 17, true, entry.done ? p.muted : p.text);
        if (entry.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        main.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(main);

        if (entry.notes != null && !entry.notes.trim().isEmpty()) {
            TextView notes = Ui.text(activity, entry.notes, 13, false, p.muted);
            notes.setPadding(0, dp(4), 0, 0);
            card.addView(notes);
        }

        LinearLayout buttons = row();
        Button edit = Ui.button(activity, "Editar", p);
        edit.setOnClickListener(v -> showEntryDialog(entry, entry.type));
        Button delete = Ui.button(activity, "Borrar", p);
        delete.setOnClickListener(v -> confirmDelete(entry));
        buttons.addView(edit, weight());
        buttons.addView(delete, weight());
        card.addView(buttons);
        return card;
    }

    // ------------------------------------------------------------------ edición

    private void showEntryDialog(AgendaEntry existing, String type) {
        Palette p = palette;
        boolean event = AgendaEntry.EVENT.equals(type);
        AgendaEntry candidate = existing == null ? new AgendaEntry() : existing.copy();

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText title = field("Título", candidate.title, InputType.TYPE_CLASS_TEXT);
        EditText date = field("Fecha (AAAA-MM-DD)",
                candidate.date == null ? selectedDate.toString() : candidate.date,
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText time = field(event ? "Hora (HH:mm)" : "Hora límite (opcional)",
                candidate.time == null ? "" : candidate.time,
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        EditText notes = field("Notas", candidate.notes,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(title, formParams());
        form.addView(date, formParams());
        form.addView(time, formParams());
        if (!event) form.addView(notes, formParams());

        ScrollView scrollForm = new ScrollView(activity);
        scrollForm.addView(form);

        AlertDialog dialog = Ui.dialog(activity)
                .setTitle(existing == null ? (event ? "Nuevo evento" : "Nueva tarea")
                        : (event ? "Editar evento" : "Editar tarea"))
                .setView(scrollForm)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String cleanTitle = title.getText().toString().trim();
                if (cleanTitle.isEmpty()) throw new IllegalArgumentException("Escribe un título.");
                LocalDate parsedDate = parseDate(date.getText().toString().trim());
                String timeText = time.getText().toString().trim();
                LocalTime parsedTime = timeText.isEmpty() ? null : parseTime(timeText);
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
                    if (existing == null) store.add(candidate);
                    else store.replace(existing, candidate);
                    selectedDate = parsedDate;
                    month = YearMonth.from(selectedDate);
                    save();
                }
                dialog.dismiss();
                refresh();
            } catch (RuntimeException ex) {
                toast(ex.getMessage() == null ? "Datos no válidos." : ex.getMessage());
            }
        }));
        dialog.show();
        Ui.style(dialog);
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Fecha no válida. Usa el formato AAAA-MM-DD.");
        }
    }

    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Hora no válida. Usa el formato HH:mm.");
        }
    }

    private void confirmDelete(AgendaEntry entry) {
        Ui.show(Ui.dialog(activity)
                .setTitle("Borrar entrada")
                .setMessage("¿Quieres borrar «" + safe(entry.title) + "»?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (dialog, which) -> {
                    synchronized (store) {
                        store.remove(entry);
                        save();
                    }
                    refresh();
                }));
    }

    private void save() {
        try {
            store.save();
        } catch (UncheckedIOException ex) {
            toast("No se pudo guardar la agenda: " + ex.getCause().getMessage());
        }
    }

    // ------------------------------------------------------------------ sincronización

    private void prepareSync() {
        if (store.isSyncEnabled()) {
            toast("El historial de sincronización ya está activo.");
            return;
        }
        Ui.show(Ui.dialog(activity)
                .setTitle("Preparar sincronización")
                .setMessage("Se registrarán las altas, modificaciones y borrados para poder intercambiarlos "
                        + "con el ordenador. ¿Activar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Activar", (dialog, which) -> {
                    try {
                        store.enableSync();
                        toast("Historial de sincronización activado.");
                        if (syncStatus != null) {
                            syncStatus.setText("Historial preparado. Conecta por Bluetooth o por cable USB.");
                        }
                        refresh();
                    } catch (RuntimeException ex) {
                        toast("No se pudo activar: " + ex.getMessage());
                    }
                }));
    }

    private void registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered || activity == null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothSyncService.ACTION_STATUS);
        filter.addAction(BluetoothSyncService.ACTION_DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(bluetoothServiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(bluetoothServiceReceiver, filter);
        }
        bluetoothReceiverRegistered = true;
    }

    private void registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(usbPermissionReceiver, filter);
        }
        usbReceiverRegistered = true;
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
            toast("Activa primero «Preparar sincronización» en ambos dispositivos.");
            return;
        }
        Ui.show(Ui.dialog(activity)
                .setTitle("Sincronización por cable USB")
                .setMessage("Conecta el móvil al ordenador con un cable de datos. En el ordenador, "
                        + "abre la Agenda y pulsa «Conectar USB».\n\n"
                        + "El ordenador cambiará el teléfono al modo Android Open Accessory. "
                        + "Cuando Android pregunte qué aplicación debe gestionar el accesorio, "
                        + "selecciona Center of Neurodivergence. Mantén esta pantalla abierta hasta que termine.")
                .setPositiveButton("Entendido", null));
        inspectUsbAccessory();
    }

    private void inspectUsbAccessory() {
        if (activity == null || activity.isFinishing() || usbSyncActive || usbPermissionPending
                || usbAccessoryDescriptor != null) return;
        UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (manager == null) return;
        UsbAccessory[] accessories = manager.getAccessoryList();
        if (accessories == null || accessories.length == 0) return;

        UsbAccessory accessory = accessories[0];
        if (manager.hasPermission(accessory)) {
            openUsbAccessory(accessory);
            return;
        }

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION).setPackage(activity.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(activity, 0, permissionIntent, flags);
        usbPermissionPending = true;
        manager.requestPermission(accessory, pendingIntent);
        if (syncStatus != null) syncStatus.setText("Esperando autorización para acceder al accesorio USB…");
    }

    private void openUsbAccessory(UsbAccessory accessory) {
        if (usbSyncActive || usbAccessoryDescriptor != null) return;
        if (BluetoothSyncService.isRunning()) {
            toast("Cierra primero la sesión Bluetooth para usar USB.");
            return;
        }
        if (!store.isSyncEnabled()) {
            toast("Activa primero el historial de sincronización.");
            return;
        }

        UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            toast("El servicio USB no está disponible.");
            return;
        }

        ParcelFileDescriptor descriptor = manager.openAccessory(accessory);
        if (descriptor == null) {
            toast("No se pudo abrir el accesorio USB.");
            return;
        }

        usbAccessoryDescriptor = descriptor;
        usbSyncActive = true;
        if (bluetoothButton != null) bluetoothButton.setEnabled(false);
        if (usbButton != null) {
            usbButton.setEnabled(true);
            usbButton.setText("Desconectar USB");
        }
        if (syncStatus != null) syncStatus.setText("Conectado por USB. Los cambios se sincronizan automáticamente.");

        syncExecutor.execute(() -> {
            String failure = null;
            try {
                FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                AgendaSyncProtocol.runServerSession(
                        input, output, store, this::mergeOnMainThread,
                        () -> usbSyncActive,
                        () -> activity.runOnUiThread(() -> {
                            if (syncStatus != null) {
                                syncStatus.setText("Conectado por USB. Los cambios se sincronizan automáticamente.");
                            }
                        }));
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
                activity.runOnUiThread(() -> {
                    if (usbButton != null) {
                        usbButton.setEnabled(!BluetoothSyncService.isRunning());
                        usbButton.setText("Conectar por cable USB");
                    }
                    if (bluetoothButton != null) bluetoothButton.setEnabled(true);
                    if (syncStatus != null) {
                        syncStatus.setText(error == null
                                ? "Conexión USB cerrada."
                                : "No se pudo completar la sincronización USB.");
                    }
                    if (error == null) refresh();
                    else toast("Error de sincronización USB: " + error);
                });
            }
        });
    }

    private void startBluetoothSync() {
        if (usbSyncActive || usbAccessoryDescriptor != null) {
            toast("Cierra primero la sesión USB para usar Bluetooth.");
            return;
        }
        if (!store.isSyncEnabled()) {
            toast("Primero activa «Preparar sincronización» en ambos dispositivos.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ctx.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
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
                if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            if (!missing.isEmpty()) {
                ctx.requestPermissions(missing.toArray(new String[0]), REQUEST_BT_PERMISSIONS);
                return;
            }
        }
        requestBluetoothDiscoverability();
    }

    private void requestBluetoothDiscoverability() {
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        if (bluetoothAdapter == null) {
            toast("Este dispositivo no tiene Bluetooth.");
            return;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                ctx.startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
                return;
            }
            Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS);
            ctx.startActivityForResult(discoverable, REQUEST_DISCOVERABLE);
        } catch (SecurityException ex) {
            toast("Falta permiso para usar Bluetooth: " + ex.getMessage());
        }
    }

    private void startBluetoothServer() {
        Intent serviceIntent = new Intent(activity, BluetoothSyncService.class)
                .setAction(BluetoothSyncService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(serviceIntent);
            } else {
                activity.startService(serviceIntent);
            }
            updateBluetoothControls(true);
            if (syncStatus != null) syncStatus.setText("Iniciando el servicio Bluetooth…");
        } catch (RuntimeException ex) {
            toast("No se pudo iniciar el servicio Bluetooth: " + ex.getMessage());
            updateBluetoothControls(false);
        }
    }

    private void stopBluetoothSync() {
        Intent serviceIntent = new Intent(activity, BluetoothSyncService.class)
                .setAction(BluetoothSyncService.ACTION_STOP);
        activity.startService(serviceIntent);
    }

    private void stopUsbSync() {
        ParcelFileDescriptor descriptor = usbAccessoryDescriptor;
        if (descriptor == null) return;
        usbSyncActive = false;
        try {
            descriptor.close();
        } catch (IOException ex) {
            toast("No se pudo cerrar USB: " + ex.getMessage());
        }
    }

    /** El protocolo corre en segundo plano, pero la agenda y la lista se modifican en el hilo de UI. */
    private void mergeOnMainThread(List<AgendaOperation> operations) throws IOException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            try {
                store.mergeOperations(operations);
                if (visible) refresh();
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

    // ------------------------------------------------------------------ utilidades de vista

    private EditText field(String hint, String value, int inputType) {
        EditText field = new EditText(activity);
        boolean multiline = inputType == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setSingleLine(!multiline);
        field.setInputType(inputType);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        Ui.styleField(field, palette);
        return field;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private LinearLayout.LayoutParams matchWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(3), 0, dp(3));
        return params;
    }

    private LinearLayout.LayoutParams formParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        return params;
    }

    private GridLayout.LayoutParams gridCell(int heightPx) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = heightPx;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(1), dp(1), dp(1), dp(1));
        return params;
    }

    private void toast(String message) {
        if (activity == null) return;
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return Ui.dp(activity, value);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
