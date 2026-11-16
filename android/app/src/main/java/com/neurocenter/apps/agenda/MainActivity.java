package com.neurocenter.apps.agenda;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final int DISCOVERABLE_SECONDS = 300;
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private volatile BluetoothSyncServer bluetoothServer;
    private BluetoothAdapter bluetoothAdapter;
    private Button bluetoothButton;
    private TextView syncStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            store = new AgendaStore(getFilesDir().toPath().resolve("agenda.json"));
            store.load();
        } catch (RuntimeException ex) {
            Toast.makeText(this, "No se pudo abrir la agenda: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        buildScreen();
        refresh();
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
        bluetoothButton.setOnClickListener(v -> startBluetoothSync());
        root.addView(bluetoothButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        syncStatus = text(store.isSyncEnabled()
                ? "Historial preparado. Elige Bluetooth para conectar con el ordenador."
                : "Activa la sincronización en ambos dispositivos para transferir cambios.",
                12, false);
        syncStatus.setPadding(0, dp(6), 0, dp(4));
        root.addView(syncStatus);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refresh() {
        if (content == null || store == null) return;
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
                entry.done = checked;
                save();
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

                if (existing == null) {
                    store.add(candidate);
                    selectedDate = parsedDate;
                } else {
                    store.replace(existing, candidate);
                    selectedDate = parsedDate;
                }
                month = YearMonth.from(selectedDate);
                save();
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
                    store.remove(entry);
                    save();
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
                        + "con el ordenador. La conexión USB/Bluetooth todavía está en desarrollo. ¿Activar?")
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

    private void startBluetoothSync() {
        if (!store.isSyncEnabled()) {
            Toast.makeText(this,
                    "Primero activa «Preparar sincronización» en ambos dispositivos.",
                    Toast.LENGTH_LONG).show();
            return;
        }

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
        }
    }

    private void startBluetoothServer() {
        final BluetoothSyncServer server;
        try {
            server = new BluetoothSyncServer(bluetoothAdapter);
            bluetoothServer = server;
        } catch (IOException | SecurityException ex) {
            Toast.makeText(this, "No se pudo preparar Bluetooth: " + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        bluetoothButton.setEnabled(false);
        syncStatus.setText("Esperando al ordenador… Mantén esta pantalla abierta. "
                + "La visibilidad estará activa durante " + (DISCOVERABLE_SECONDS / 60) + " minutos.");
        syncExecutor.execute(() -> {
            String failure = null;
            try {
                server.waitForOneSync(store, this::mergeOnMainThread);
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // Ya se está cerrando la conexión.
                }
                if (bluetoothServer == server) bluetoothServer = null;
                final String error = failure;
                runOnUiThread(() -> {
                    bluetoothButton.setEnabled(true);
                    if (error == null) {
                        syncStatus.setText("Agenda sincronizada correctamente.");
                        Toast.makeText(this, "Agenda sincronizada.", Toast.LENGTH_LONG).show();
                        refresh();
                    } else {
                        syncStatus.setText("No se pudo completar la sincronización.");
                        Toast.makeText(this, "Error de sincronización: " + error,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
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
        BluetoothSyncServer server = bluetoothServer;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // La actividad está finalizando.
            }
        }
        syncExecutor.shutdownNow();
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
