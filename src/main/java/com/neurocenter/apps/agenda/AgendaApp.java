package com.neurocenter.apps.agenda;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;
import com.neurocenter.api.services.AgendaService;
import com.neurocenter.api.services.ExternalTask;
import com.neurocenter.api.plugin.ServiceRegistry;
import com.neurocenter.core.AppPaths;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agenda con calendario mensual y tareas por día.
 * Los avisos de los eventos funcionan desde que arranca la aplicación (no hace falta abrir la agenda).
 */
public final class AgendaApp implements NeuroApp {

    private final AgendaStore store;
    private AppContext context;
    private Timer reminderTimer;
    private AgendaPanel agendaPanel;


    public AgendaApp(ServiceRegistry services) {
        store = new AgendaStore(AppPaths.appDataDir("agenda").resolve("agenda.json"));
        store.load();
        services.register(AgendaService.class, (source, tasks) -> {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("El origen de importación es obligatorio.");
            int changed = 0;
            for (ExternalTask task : tasks) {
                AgendaEntry existing = store.all().stream()
                        .filter(item -> source.equals(item.externalSource) && task.externalId().equals(item.externalId))
                        .findFirst().orElse(null);
                if (existing == null) {
                    existing = new AgendaEntry();
                    existing.externalSource = source;
                    existing.externalId = task.externalId();
                    store.add(existing);
                }
                existing.type = AgendaEntry.TASK;
                existing.title = task.title();
                existing.date = task.dueDate() == null ? null : task.dueDate().toString();
                existing.time = task.dueTime() == null ? null : task.dueTime().toString();
                existing.notes = task.notes();
                existing.done = task.completed();
                changed++;
            }
            if (changed > 0) store.save();
            return changed;
        });
    }

    @Override public String id() { return "agenda"; }
    @Override public String name() { return "Agenda"; }

    @Override public String description() {
        return "Calendario y tareas por día, con avisos para tus eventos.";
    }

    @Override
    public void start(AppContext ctx) {
        this.context = ctx;
        ctx.services().register(AgendaService.class, (source, tasks) -> {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("El origen de importación es obligatorio.");
            int changed = 0;
            for (ExternalTask task : tasks) {
                AgendaEntry existing = store.all().stream()
                        .filter(item -> source.equals(item.externalSource) && task.externalId().equals(item.externalId))
                        .findFirst().orElse(null);
                if (existing == null) {
                    existing = new AgendaEntry();
                    existing.externalSource = source;
                    existing.externalId = task.externalId();
                    store.add(existing);
                }
                existing.type = AgendaEntry.TASK;
                existing.title = task.title();
                existing.date = task.dueDate() == null ? null : task.dueDate().toString();
                existing.time = task.dueTime() == null ? null : task.dueTime().toString();
                existing.notes = task.notes();
                existing.done = task.completed();
                changed++;
            }
            if (changed > 0) store.save();
            return changed;
        });


        reminderTimer = new Timer(30_000, e -> checkReminders());
        reminderTimer.setInitialDelay(3_000);
        reminderTimer.start();
    }

    @Override
    public JComponent createPanel(AppContext ctx) {
        agendaPanel = new AgendaPanel(store, ctx);
        return agendaPanel;
    }

    @Override
    public void stop() {
        if (reminderTimer != null) reminderTimer.stop();
        if (agendaPanel != null) {
            agendaPanel.closeSyncSession();
            agendaPanel = null;
        }
    }

    private void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<String> notifications = new ArrayList<>();
        boolean changed = false;

        for (AgendaEntry e : store.all()) {
            if (!e.isEvent()) continue;
            LocalDate eventDate = e.localDate();
            LocalDateTime due = e.due();
            if (eventDate == null || due == null) continue;

            // 1) Aviso del día anterior: salta en cuanto llega el día anterior (o después, antes de que pase el evento)
            if (e.remindDayBefore && !e.notifiedDayBefore) {
                LocalDate dayBefore = eventDate.minusDays(1);
                if (!today.isBefore(dayBefore) && due.isAfter(now)) {
                    e.notifiedDayBefore = true;
                    changed = true;
                    String when = today.equals(dayBefore) ? "Mañana" : "El " + e.date;
                    notifications.add("• " + when + " a las " + e.time + " — " + e.title + " (aviso del día anterior)");
                }
            }

            // 2) Aviso personalizado (X minutos/horas/días antes)
            if (e.remindCustom && !e.notifiedCustom) {
                LocalDateTime trigger = e.customTrigger();
                if (trigger != null && !trigger.isAfter(now) && due.isAfter(now)) {
                    e.notifiedCustom = true;
                    changed = true;
                    String dateSuffix = today.equals(eventDate) ? "" : " (" + e.date + ")";
                    notifications.add("• " + e.time + dateSuffix + " — " + e.title
                            + " (" + formatAdvance(e.remindBeforeMinutes) + ")");
                }
            }
        }

        if (!changed || notifications.isEmpty()) return;

        // Se guardan antes de mostrar el diálogo para que no se repita el aviso mientras está abierto.
        try {
            store.save();
        } catch (UncheckedIOException ex) {
            ex.printStackTrace();
        }

        StringBuilder msg = new StringBuilder("Tienes avisos de eventos en tu agenda:\n\n");
        for (String line : notifications) {
            msg.append(line).append("\n");
        }
        context.requestAttention();
        JOptionPane.showMessageDialog(context.window(), msg.toString(), "Aviso de la agenda",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static String formatAdvance(int minutes) {
        if (minutes % 1440 == 0) {
            int days = minutes / 1440;
            return days == 1 ? "falta 1 día" : "faltan " + days + " días";
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours == 1 ? "falta 1 hora" : "faltan " + hours + " horas";
        }
        return minutes == 1 ? "falta 1 minuto" : "faltan " + minutes + " minutos";
    }
}