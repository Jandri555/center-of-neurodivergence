package com.neurocenter.programs.agenda;

import com.neurocenter.api.NeuroProgram;
import com.neurocenter.api.ProgramContext;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Agenda con calendario mensual y tareas por día.
 * Los avisos funcionan desde que arranca la aplicación (no hace falta abrir la agenda).
 */
public final class AgendaProgram implements NeuroProgram {

    private AgendaStore store;
    private ProgramContext context;
    private Timer reminderTimer;

    @Override public String id() { return "agenda"; }

    @Override public String name() { return "Agenda"; }

    @Override public String description() {
        return "Calendario y tareas por día, con avisos a la hora que elijas.";
    }

    @Override
    public void start(ProgramContext ctx) {
        this.context = ctx;
        store = new AgendaStore(ctx.dataDir().resolve("agenda.json"));
        store.load();
        reminderTimer = new Timer(30_000, e -> checkReminders());
        reminderTimer.setInitialDelay(3_000);
        reminderTimer.start();
    }

    @Override
    public JComponent createPanel(ProgramContext ctx) {
        return new AgendaPanel(store, ctx);
    }

    @Override
    public void stop() {
        if (reminderTimer != null) reminderTimer.stop();
    }

    private void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<AgendaEntry> due = store.all().stream()
                .filter(e -> e.remind && !e.done && !e.notified)
                .filter(e -> {
                    LocalDateTime d = e.due();
                    return d != null && !d.isAfter(now);
                })
                .sorted(Comparator.comparing(AgendaEntry::due))
                .toList();
        if (due.isEmpty()) return;

        // Se marcan antes de mostrar el diálogo para que no se repita el aviso mientras está abierto.
        due.forEach(e -> e.notified = true);
        try {
            store.save();
        } catch (UncheckedIOException ex) {
            ex.printStackTrace();
        }

        LocalDate today = LocalDate.now();
        StringBuilder msg = new StringBuilder("Tienes avisos de tu agenda:\n");
        for (AgendaEntry e : due) {
            msg.append("\n• ").append(e.time).append("  ").append(e.title);
            if (!today.equals(e.localDate())) msg.append("  (").append(e.date).append(")");
        }
        context.requestAttention();
        JOptionPane.showMessageDialog(context.window(), msg.toString(), "Aviso de la agenda",
                JOptionPane.INFORMATION_MESSAGE);
    }
}
