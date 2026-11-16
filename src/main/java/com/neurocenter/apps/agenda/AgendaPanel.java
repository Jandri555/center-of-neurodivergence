package com.neurocenter.apps.agenda;

import com.neurocenter.api.AppContext;
import com.neurocenter.ui.BadgeIcon;
import com.neurocenter.ui.RoundedPanel;

import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.font.TextAttribute;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agenda con dos vistas:
 *  - Calendario: mes a la izquierda y lo que hay en el día seleccionado a la derecha.
 *  - Pendientes: todas las tareas sin hacer, ordenadas por fecha límite.
 * Hay dos tipos de entrada: tareas (con fecha límite) y eventos (con fecha, hora y avisos).
 */
final class AgendaPanel extends JPanel {

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ES);
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter DEADLINE_YEAR = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES);
    private static final String[] WEEKDAYS = { "L", "M", "X", "J", "V", "S", "D" };

    private static final String VIEW_CALENDAR = "calendar";
    private static final String VIEW_PENDING = "pending";
    private static final Color OVERDUE = new Color(0xDB4B4B);

    private final AgendaStore store;
    private final AppContext ctx;

    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private boolean pendingView = true; // la agenda se abre en Pendientes

    private final CardLayout viewCards = new CardLayout();
    private final JPanel views = new JPanel(viewCards);
    private final JToggleButton calendarBtn = new JToggleButton("Calendario");
    private final JToggleButton pendingBtn = new JToggleButton("Pendientes");

    private final JLabel monthLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel daysGrid = new JPanel(new GridLayout(6, 7, 4, 4));
    private final JLabel dayTitle = new JLabel(" ");
    private final JLabel daySummary = new JLabel(" ");
    private final JPanel dayList = new JPanel(new GridBagLayout());

    private final JLabel pendingSummary = new JLabel(" ");
    private final JPanel pendingList = new JPanel(new GridBagLayout());

    AgendaPanel(AgendaStore store, AppContext ctx) {
        super(new BorderLayout(0, 18));
        this.store = store;
        this.ctx = ctx;
        setBorder(new EmptyBorder(24, 28, 24, 28));

        views.add(buildPendingView(), VIEW_PENDING);
        views.add(buildCalendarView(), VIEW_CALENDAR);

        add(buildTopBar(), BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        refresh();
    }

    // ------------------------------------------------------------------ construcción

    private JComponent buildTopBar() {
        ButtonGroup group = new ButtonGroup();
        group.add(pendingBtn);
        group.add(calendarBtn);
        styleAgendaToggle(pendingBtn);
        styleAgendaToggle(calendarBtn);
        pendingBtn.setSelected(true);
        pendingBtn.addActionListener(e -> showView(true));
        calendarBtn.addActionListener(e -> showView(false));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(pendingBtn);
        left.add(calendarBtn);

        JButton newTask = new JButton("Nueva tarea");
        styleAgendaButton(newTask);
        newTask.addActionListener(e -> addEntry(AgendaEntry.TASK));
        JButton newEvent = new JButton("Nuevo evento");
        styleAgendaButton(newEvent);
        newEvent.addActionListener(e -> addEntry(AgendaEntry.EVENT));

        JButton prepareSync = new JButton(store.isSyncEnabled()
                ? "Historial de sincronización activo" : "Preparar sincronización");
        styleAgendaButton(prepareSync);
        prepareSync.setEnabled(!store.isSyncEnabled());
        prepareSync.setToolTipText("Activa el registro de cambios para futuras conexiones con Android");
        prepareSync.addActionListener(e -> prepareSync());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(prepareSync);
        right.add(newTask);
        right.add(newEvent);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** Mantiene los controles de Agenda planos y coherentes con la barra de Bionic PDF. */
    private static void styleAgendaButton(AbstractButton button) {
        // Botón normal: conserva el borde sutil y recupera los estados hover/pressed de FlatLaf.
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setFocusable(false);
    }

    private static void styleAgendaToggle(JToggleButton button) {
        // Las vistas son pestañas: subrayado al seleccionar y fondo al pasar el ratón.
        button.putClientProperty("JButton.buttonType", "tab");
        button.setFocusable(false);
    }

    private void prepareSync() {
        String message = "Se activará un historial de operaciones de la agenda. Guardará las altas, "
                + "modificaciones, cambios de estado y borrados para poder intercambiarlos con Android.\\n\\n"
                + "El historial no sustituye la agenda actual y no se vacía automáticamente: se conservará "
                + "hasta que exista un mecanismo de sincronización con confirmación de recepción.\\n\\n"
                + "La conexión real por USB y Bluetooth todavía no está disponible. ¿Activar el historial?";
        int answer = JOptionPane.showConfirmDialog(
                ctx.window(), message, "Preparar sincronización",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        try {
            store.enableSync();
            JOptionPane.showMessageDialog(ctx.window(),
                    "Historial activado. A partir de ahora, los cambios de la agenda se registrarán "
                            + "para la futura sincronización con Android.",
                    "Sincronización preparada", JOptionPane.INFORMATION_MESSAGE);
            refresh();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(ctx.window(),
                    "No se pudo activar el historial: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showView(boolean pending) {
        pendingView = pending;
        viewCards.show(views, pending ? VIEW_PENDING : VIEW_CALENDAR);
    }

    private JComponent buildCalendarView() {
        JPanel view = new JPanel(new BorderLayout(28, 0));
        view.add(buildCalendarSide(), BorderLayout.WEST);
        view.add(buildDaySide(), BorderLayout.CENTER);
        return view;
    }

    private JComponent buildCalendarSide() {
        JButton prev = new JButton("◀");
        JButton next = new JButton("▶");
        styleAgendaButton(prev);
        styleAgendaButton(next);
        prev.addActionListener(e -> { month = month.minusMonths(1); refresh(); });
        next.addActionListener(e -> { month = month.plusMonths(1); refresh(); });
        monthLabel.setFont(monthLabel.getFont().deriveFont(Font.BOLD, 17f));

        JPanel nav = new JPanel(new BorderLayout(8, 0));
        nav.add(prev, BorderLayout.WEST);
        nav.add(monthLabel, BorderLayout.CENTER);
        nav.add(next, BorderLayout.EAST);

        JPanel weekdays = new JPanel(new GridLayout(1, 7, 4, 4));
        for (String d : WEEKDAYS) {
            JLabel l = new JLabel(d, SwingConstants.CENTER);
            l.setForeground(muted());
            weekdays.add(l);
        }

        daysGrid.setPreferredSize(new Dimension(340, 6 * 46 + 5 * 4));

        JPanel grid = new JPanel(new BorderLayout(0, 6));
        grid.add(weekdays, BorderLayout.NORTH);
        grid.add(daysGrid, BorderLayout.CENTER);

        JButton today = new JButton("Ir a hoy");
        styleAgendaButton(today);
        today.addActionListener(e -> {
            selected = LocalDate.now();
            month = YearMonth.from(selected);
            refresh();
        });

        JPanel side = new JPanel(new BorderLayout(0, 12));
        side.add(nav, BorderLayout.NORTH);
        side.add(grid, BorderLayout.CENTER);
        side.add(today, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.add(side, BorderLayout.NORTH);
        return outer;
    }

    private JComponent buildDaySide() {
        dayTitle.setFont(dayTitle.getFont().deriveFont(Font.BOLD, 20f));
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2));
        titles.add(dayTitle);
        titles.add(daySummary);
        return scrollingSide(titles, dayList);
    }

    private JComponent buildPendingView() {
        JLabel title = new JLabel("Tareas pendientes");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2));
        titles.add(title);
        titles.add(pendingSummary);
        return scrollingSide(titles, pendingList);
    }

    /** Cabecera fija arriba y, debajo, una lista con scroll vertical que se adapta al ancho. */
    private static JComponent scrollingSide(JComponent head, JPanel list) {
        JPanel holder = new WidthTrackingPanel();
        holder.setOpaque(false);
        list.setOpaque(false);
        holder.add(list, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel side = new JPanel(new BorderLayout(0, 16));
        side.setOpaque(false);
        side.add(head, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    // ------------------------------------------------------------------ refresco

    private void refresh() {
        rebuildCalendar();
        rebuildDay();
        rebuildPending();
    }

    private void rebuildCalendar() {
        monthLabel.setText(capitalize(month.getMonth().getDisplayName(TextStyle.FULL, ES)) + " " + month.getYear());
        daysGrid.removeAll();

        Set<LocalDate> busy = store.all().stream()
                .map(AgendaEntry::localDate)
                .filter(Objects::nonNull)
                .filter(d -> YearMonth.from(d).equals(month))
                .collect(Collectors.toSet());

        int offset = month.atDay(1).getDayOfWeek().getValue() - 1; // la semana empieza en lunes
        for (int i = 0; i < offset; i++) daysGrid.add(new JLabel());

        LocalDate today = LocalDate.now();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            daysGrid.add(dayButton(date, date.equals(selected), date.equals(today), busy.contains(date)));
        }
        while (daysGrid.getComponentCount() < 42) daysGrid.add(new JLabel());

        daysGrid.revalidate();
        daysGrid.repaint();
    }

    private JButton dayButton(LocalDate date, boolean isSelected, boolean isToday, boolean hasEntries) {
        String number = isToday ? "<b>" + date.getDayOfMonth() + "</b>" : String.valueOf(date.getDayOfMonth());
        String dot = hasEntries ? "&#8226;" : "&nbsp;";
        JButton b = new JButton("<html><center>" + number + "<br>" + dot + "</center></html>");
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setFocusable(false);
        if (isSelected) {
            b.putClientProperty("FlatLaf.style", "background: " + hex(RoundedPanel.accent()) + "; foreground: #FFFFFF");
        } else if (isToday) {
            b.setForeground(RoundedPanel.accent());
        }
        b.addActionListener(e -> {
            selected = date;
            rebuildCalendar();
            rebuildDay();
        });
        return b;
    }

    private void rebuildDay() {
        dayTitle.setText(capitalize(DAY_TITLE.format(selected)));
        List<AgendaEntry> list = store.on(selected);
        long pending = list.stream().filter(e -> !e.isEvent() && !e.done).count();
        long done = list.stream().filter(e -> !e.isEvent() && e.done).count();
        long events = list.stream().filter(AgendaEntry::isEvent).count();

        List<String> parts = new ArrayList<>();
        if (pending > 0) parts.add(plural(pending, "pendiente", "pendientes"));
        if (done > 0) parts.add(plural(done, "hecha", "hechas"));
        if (events > 0) parts.add(plural(events, "evento", "eventos"));
        daySummary.setText(parts.isEmpty() ? "Sin nada planeado" : String.join(", ", parts));
        daySummary.setForeground(muted());

        fillList(dayList, list, false, "Nada planeado para este día.");
    }

    private void rebuildPending() {
        List<AgendaEntry> pending = store.pendingTasks();
        LocalDateTime now = LocalDateTime.now();
        long overdue = pending.stream().filter(e -> e.isOverdue(now)).count();

        pendingBtn.setText(pending.isEmpty() ? "Pendientes" : "Pendientes (" + pending.size() + ")");
        if (pending.isEmpty()) {
            pendingSummary.setText("Todo al día");
        } else {
            pendingSummary.setText(plural(pending.size(), "tarea pendiente", "tareas pendientes")
                    + (overdue > 0 ? ", " + plural(overdue, "vencida", "vencidas") : ""));
        }
        pendingSummary.setForeground(overdue > 0 ? OVERDUE : muted());

        fillList(pendingList, pending, true, "No tienes tareas pendientes.");
    }

    private void fillList(JPanel panel, List<AgendaEntry> entries, boolean withDeadline, String emptyText) {
        panel.removeAll();
        if (entries.isEmpty()) {
            JLabel empty = new JLabel(emptyText, SwingConstants.CENTER);
            empty.setForeground(muted());
            empty.setBorder(new EmptyBorder(48, 0, 0, 0));
            panel.add(empty, rowConstraints(0));
        }
        int y = 0;
        for (AgendaEntry e : entries) panel.add(buildRow(e, withDeadline), rowConstraints(y++));
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Una fila de la lista. En la vista de pendientes ({@code withDeadline}) se muestra la fecha límite
     * a la derecha; en la vista del día, la hora va delante del título.
     */
    private JComponent buildRow(AgendaEntry e, boolean withDeadline) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(10, 0));
        row.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Las tareas se marcan como hechas; los eventos no tienen casilla.
        JComponent lead;
        if (e.isEvent()) {
            JLabel tag = new JLabel(new BadgeIcon("E", "evento", 20));
            tag.setToolTipText("Evento");
            lead = tag;
        } else {
            JCheckBox done = new JCheckBox();
            done.setOpaque(false);
            done.setSelected(e.done);
            done.setToolTipText("Marcar como hecha");
            done.addActionListener(ev -> {
                e.done = done.isSelected();
                persist();
                refresh();
            });
            lead = done;
        }

        String time = !withDeadline && e.localTime() != null ? e.time + "   " : "";
        JLabel title = new JLabel(time + e.title);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setMinimumSize(new Dimension(0, title.getPreferredSize().height));
        if (e.done) {
            Map<TextAttribute, Object> attrs = new HashMap<>(title.getFont().getAttributes());
            attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            title.setFont(title.getFont().deriveFont(attrs));
            title.setForeground(muted());
        }

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(title);

        String sub = subtitle(e, withDeadline);
        if (!sub.isEmpty()) {
            JLabel subLabel = new JLabel(sub);
            subLabel.setForeground(muted());
            subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            subLabel.setMinimumSize(new Dimension(0, subLabel.getPreferredSize().height));
            text.add(subLabel);
        }
        if (e.notes != null && !e.notes.isBlank()) {
            row.setToolTipText("<html>" + esc(e.notes).replace("\n", "<br>") + "</html>");
        }

        JButton edit = new JButton("Editar");
        styleAgendaButton(edit);
        edit.addActionListener(ev -> editEntry(e));
        JButton delete = new JButton("Borrar");
        styleAgendaButton(delete);
        delete.addActionListener(ev -> deleteEntry(e));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        if (withDeadline) actions.add(deadlineBox(e));
        actions.add(edit);
        actions.add(delete);

        row.add(lead, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        row.add(actions, BorderLayout.EAST);
        row.setMinimumSize(new Dimension(0, row.getPreferredSize().height));
        return row;
    }

    private static String subtitle(AgendaEntry e, boolean withDeadline) {
        if (e.isEvent()) {
            List<String> reminders = new ArrayList<>();
            if (e.remindDayBefore) reminders.add("día anterior");
            if (e.remindCustom && e.remindBeforeMinutes > 0) {
                reminders.add(formatAdvance(e.remindBeforeMinutes));
            }
            if (reminders.isEmpty()) return "Evento";
            return "Evento. Aviso: " + String.join(", ", reminders);
        }
        List<String> parts = new ArrayList<>();
        if (!withDeadline) parts.add("Fecha límite");
        String firstLine = e.notes == null ? "" : e.notes.strip().lines().findFirst().orElse("");
        if (!firstLine.isEmpty()) parts.add(firstLine);
        return String.join(". ", parts);
    }

    private static String formatAdvance(int minutes) {
        if (minutes % 1440 == 0) {
            long days = minutes / 1440;
            return plural(days, "día antes", "días antes");
        }
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return plural(hours, "hora antes", "horas antes");
        }
        return minutes + " min antes";
    }

    /** Fecha límite y cuánto falta, a la derecha de cada tarea pendiente. Las vencidas van en rojo. */
    private JComponent deadlineBox(AgendaEntry e) {
        LocalDateTime now = LocalDateTime.now();
        boolean overdue = e.isOverdue(now);

        JLabel date = new JLabel(deadlineText(e, now.toLocalDate()), SwingConstants.RIGHT);
        date.setFont(date.getFont().deriveFont(Font.BOLD));

        JLabel relative = new JLabel(relativeText(e, now), SwingConstants.RIGHT);
        relative.setForeground(overdue ? OVERDUE : muted());
        if (overdue) date.setForeground(OVERDUE);

        JPanel box = new JPanel(new GridLayout(2, 1, 0, 2));
        box.setOpaque(false);
        box.add(date);
        box.add(relative);
        return box;
    }

    private static String deadlineText(AgendaEntry e, LocalDate today) {
        LocalDate d = e.localDate();
        if (d == null) return "Sin fecha";
        String text = capitalize((d.getYear() == today.getYear() ? DEADLINE : DEADLINE_YEAR).format(d));
        return e.localTime() != null ? text + ", " + e.time : text;
    }

    private static String relativeText(AgendaEntry e, LocalDateTime now) {
        LocalDate d = e.localDate();
        if (d == null) return " ";
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), d);
        if (days < 0) return "Vencida hace " + plural(-days, "día", "días");
        if (days == 0) return e.isOverdue(now) ? "Vencida hoy" : "Vence hoy";
        if (days == 1) return "Vence mañana";
        if (days <= 30) return "Vence en " + days + " días";
        return " ";
    }

    // ------------------------------------------------------------------ acciones

    private void addEntry(String type) {
        AgendaEntry e = askEntry(null, type);
        if (e == null) return;
        store.add(e);
        persist();
        jumpTo(e);
        refresh();
    }

    private void editEntry(AgendaEntry e) {
        AgendaEntry updated = askEntry(e, e.type);
        if (updated == null) return;
        store.replace(e, updated);
        persist();
        jumpTo(updated);
        refresh();
    }

    private void deleteEntry(AgendaEntry e) {
        int r = JOptionPane.showConfirmDialog(ctx.window(), "¿Borrar «" + e.title + "»?",
                e.isEvent() ? "Borrar evento" : "Borrar tarea",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        store.remove(e);
        persist();
        refresh();
    }

    private void jumpTo(AgendaEntry e) {
        LocalDate d = e.localDate();
        if (d != null) {
            selected = d;
            month = YearMonth.from(d);
        }
    }

    private void persist() {
        try {
            store.save();
        } catch (UncheckedIOException ex) {
            JOptionPane.showMessageDialog(ctx.window(),
                    "No se ha podido guardar la agenda:\n" + ex.getCause().getMessage(),
                    "Error al guardar", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Muestra el diálogo de tarea o de evento. Si {@code existing} no es null se modifica esa misma entrada.
     * Devuelve la entrada, o null si el usuario cancela.
     */
    private AgendaEntry askEntry(AgendaEntry existing, String type) {
        boolean event = AgendaEntry.EVENT.equals(type);
        LocalDate date0 = existing != null && existing.localDate() != null
                ? existing.localDate()
                : (pendingView ? LocalDate.now() : selected);
        LocalTime time0 = existing != null && existing.localTime() != null
                ? existing.localTime()
                : LocalTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS);

        JTextField title = new JTextField(existing == null ? "" : existing.title, 26);

        JSpinner dateSpinner = new JSpinner(new SpinnerDateModel(toDate(date0), null, null, Calendar.DAY_OF_MONTH));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd/MM/yyyy"));

        // Hora: obligatoria en un evento, opcional en una tarea
        JCheckBox withTime = new JCheckBox("Con hora", existing != null && existing.localTime() != null);
        JSpinner hour = new JSpinner(new SpinnerNumberModel(time0.getHour(), 0, 23, 1));
        JSpinner minute = new JSpinner(new SpinnerNumberModel(time0.getMinute(), 0, 59, 5));
        hour.setEditor(new JSpinner.NumberEditor(hour, "00"));
        minute.setEditor(new JSpinner.NumberEditor(minute, "00"));

        // Avisos (solo para eventos):
        // Por defecto, al crear un evento nuevo viene marcada la opción de avisar el día anterior
        boolean defaultDayBefore = existing == null ? true : existing.remindDayBefore;
        JCheckBox remindDayBefore = new JCheckBox("Avisar el día anterior", defaultDayBefore);

        boolean defaultCustom = existing != null && existing.remindCustom;
        int initMins = (existing != null && existing.remindBeforeMinutes > 0) ? existing.remindBeforeMinutes : 30;
        int initVal;
        int initUnitIdx; // 0 = minutos, 1 = horas, 2 = días
        if (initMins % 1440 == 0) {
            initVal = initMins / 1440;
            initUnitIdx = 2;
        } else if (initMins % 60 == 0) {
            initVal = initMins / 60;
            initUnitIdx = 1;
        } else {
            initVal = initMins;
            initUnitIdx = 0;
        }

        JCheckBox remindCustom = new JCheckBox("Recordar antes:", defaultCustom);
        JSpinner customAmount = new JSpinner(new SpinnerNumberModel(Math.max(1, initVal), 1, 999, 1));
        JComboBox<String> customUnit = new JComboBox<>(new String[] { "minutos", "horas", "días" });
        customUnit.setSelectedIndex(initUnitIdx);

        JTextArea notes = new JTextArea(existing == null || existing.notes == null ? "" : existing.notes, 4, 26);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);

        Runnable syncTime = () -> {
            boolean t = event || withTime.isSelected();
            hour.setEnabled(t);
            minute.setEnabled(t);
        };
        withTime.addActionListener(ev -> syncTime.run());
        syncTime.run();

        Runnable syncCustomRemind = () -> {
            boolean c = remindCustom.isSelected();
            customAmount.setEnabled(c);
            customUnit.setEnabled(c);
        };
        remindCustom.addActionListener(ev -> syncCustomRemind.run());
        syncCustomRemind.run();

        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (!event) timeRow.add(withTime);
        timeRow.add(hour);
        timeRow.add(new JLabel(":"));
        timeRow.add(minute);

        JPanel customRemindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        customRemindRow.add(remindCustom);
        customRemindRow.add(customAmount);
        customRemindRow.add(customUnit);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addFormRow(form, row++, "Título", title);
        addFormRow(form, row++, event ? "Día" : "Fecha límite", dateSpinner);
        addFormRow(form, row++, event ? "Hora" : "Hora límite", timeRow);
        if (event) {
            addFormRow(form, row++, "Avisos", remindDayBefore);
            addFormRow(form, row++, "", customRemindRow);
        } else {
            addFormRow(form, row++, "Notas", new JScrollPane(notes));
        }
        focusOnShow(title);

        String dialogTitle = existing != null
                ? (event ? "Editar evento" : "Editar tarea")
                : (event ? "Nuevo evento" : "Nueva tarea");

        while (true) {
            int r = JOptionPane.showConfirmDialog(ctx.window(), form, dialogTitle,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;

            String ttl = title.getText().strip();
            if (ttl.isEmpty()) {
                JOptionPane.showMessageDialog(ctx.window(),
                        event ? "Ponle un título al evento." : "Ponle un título a la tarea.",
                        "Falta el título", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            for (JSpinner s : new JSpinner[] { dateSpinner, hour, minute, customAmount }) {
                try {
                    s.commitEdit();
                } catch (ParseException ignored) {
                    // se queda con el último valor válido
                }
            }

            AgendaEntry e = existing != null ? existing.copy() : new AgendaEntry();
            boolean hasTime = event || withTime.isSelected();
            e.type = type;
            e.title = ttl;
            e.date = fromDate((Date) dateSpinner.getValue()).toString();
            e.time = hasTime
                    ? LocalTime.of(((Number) hour.getValue()).intValue(), ((Number) minute.getValue()).intValue()).toString()
                    : null;

            if (event) {
                e.notes = "";
                e.done = false;
                e.remind = false;
                e.notified = false;

                e.remindDayBefore = remindDayBefore.isSelected();
                e.remindCustom = remindCustom.isSelected();

                int amount = ((Number) customAmount.getValue()).intValue();
                int unitIdx = customUnit.getSelectedIndex();
                int multiplier = (unitIdx == 2) ? 1440 : (unitIdx == 1 ? 60 : 1);
                e.remindBeforeMinutes = amount * multiplier;

                LocalDateTime now = LocalDateTime.now();
                LocalDateTime due = e.due();

                // Si el evento aún está en el futuro, dejamos activos los avisos configurados;
                // si el evento ya pasó, los marcamos como notificados para no dispararlos retroactivamente.
                boolean eventInFuture = due != null && due.isAfter(now);
                e.notifiedDayBefore = !e.remindDayBefore || !eventInFuture;
                e.notifiedCustom = !e.remindCustom || !eventInFuture;
            } else {
                e.notes = notes.getText().strip();
                e.remind = false;
                e.notified = false;
                e.remindDayBefore = false;
                e.notifiedDayBefore = false;
                e.remindCustom = false;
                e.notifiedCustom = false;
            }
            return e;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static void addFormRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.NORTHWEST;
        l.insets = new Insets(6, 0, 6, 12);
        form.add(new JLabel(label), l);

        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.anchor = GridBagConstraints.NORTHWEST;
        f.insets = new Insets(4, 0, 4, 0);
        form.add(field, f);
    }

    private static GridBagConstraints rowConstraints(int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);
        return c;
    }

    private static void focusOnShow(JComponent c) {
        c.addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent e) {
                SwingUtilities.invokeLater(c::requestFocusInWindow);
                c.removeAncestorListener(this);
            }
            @Override public void ancestorRemoved(AncestorEvent e) {}
            @Override public void ancestorMoved(AncestorEvent e) {}
        });
    }

    private static Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDate fromDate(Date d) {
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Panel que se adapta al ancho visible del JScrollPane (así los textos largos se recortan en lugar de desbordar). */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}