package com.neurocenter.apps.agenda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgendaSyncJournalTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void syncHistoryCarriesCreatesUpdatesAndDeletesAndIsIdempotent() {
        AgendaStore desktop = store("desktop");
        AgendaEntry task = new AgendaEntry();
        task.title = "Entregar práctica";
        task.date = LocalDate.of(2026, 11, 20).toString();
        desktop.add(task);
        desktop.save();

        // Al activar, los datos que ya existían se convierten en la línea base.
        desktop.enableSync();
        assertEquals(1, desktop.exportOperations().size());

        task.done = true;
        desktop.save();
        assertEquals(2, desktop.exportOperations().size());

        AgendaStore phone = store("phone");
        phone.enableSync();
        phone.mergeOperations(desktop.exportOperations());
        assertEquals(1, phone.all().size());
        assertTrue(phone.all().get(0).done);

        // Reenviar el mismo historial no debe crear operaciones duplicadas.
        phone.mergeOperations(desktop.exportOperations());
        assertEquals(2, phone.exportOperations().size());

        desktop.remove(task);
        desktop.save();
        assertEquals(3, desktop.exportOperations().size());

        phone.mergeOperations(desktop.exportOperations());
        assertTrue(phone.all().isEmpty(), "El borrado debe propagarse al segundo dispositivo.");
    }

    @Test
    void concurrentDeleteWinsOverOfflineEditToAvoidResurrectingEntry() {
        AgendaStore desktop = store("desktop");
        AgendaEntry event = new AgendaEntry();
        event.type = AgendaEntry.EVENT;
        event.title = "Cita";
        event.date = LocalDate.of(2026, 12, 1).toString();
        event.time = "10:00";
        desktop.add(event);
        desktop.save();
        desktop.enableSync();

        AgendaStore phone = store("phone");
        phone.enableSync();
        phone.mergeOperations(desktop.exportOperations());

        // Ambos dispositivos parten de la misma versión, pero trabajan desconectados.
        desktop.remove(event);
        desktop.save();

        AgendaEntry phoneEvent = phone.all().get(0);
        phoneEvent.title = "Cita cambiada sin conexión";
        phone.save();

        List<AgendaOperation> desktopOps = desktop.exportOperations();
        List<AgendaOperation> phoneOps = phone.exportOperations();

        desktop.mergeOperations(phoneOps);
        phone.mergeOperations(desktopOps);

        assertTrue(desktop.all().isEmpty());
        assertTrue(phone.all().isEmpty(),
                "Un cambio offline concurrente no debe resucitar un elemento borrado en otro dispositivo.");
    }

    @Test
    void reminderDeliveryFlagsRemainLocalToEachDevice() {
        AgendaStore desktop = store("desktop");
        AgendaEntry event = new AgendaEntry();
        event.type = AgendaEntry.EVENT;
        event.title = "Reunión";
        event.date = LocalDate.of(2026, 12, 1).toString();
        event.time = "12:00";
        event.remindDayBefore = true;
        event.notifiedDayBefore = true;
        desktop.add(event);
        desktop.save();
        desktop.enableSync();

        AgendaStore phone = store("phone");
        phone.enableSync();
        phone.mergeOperations(desktop.exportOperations());
        AgendaEntry copied = phone.all().get(0);

        assertTrue(copied.remindDayBefore);
        assertFalse(copied.notifiedDayBefore,
                "La entrega del aviso es local; cada dispositivo debe poder notificar.");
    }

    @Test
    void operationJournalReconstructsAgendaAfterRestart() {
        AgendaStore desktop = store("restart");
        AgendaEntry task = new AgendaEntry();
        task.title = "Comprar material";
        task.date = LocalDate.of(2026, 11, 22).toString();
        desktop.add(task);
        desktop.save();
        desktop.enableSync();

        task.title = "Comprar material de clase";
        desktop.save();

        AgendaStore reopened = new AgendaStore(
                temporaryDirectory.resolve("restart").resolve("agenda.json"));
        reopened.load();

        assertTrue(reopened.isSyncEnabled());
        assertEquals(2, reopened.exportOperations().size());
        assertEquals("Comprar material de clase", reopened.all().get(0).title);
    }

    @Test
    void changingReminderScheduleResetsLocalDeliveryState() {
        AgendaStore desktop = store("reminder-desktop");
        AgendaEntry event = new AgendaEntry();
        event.type = AgendaEntry.EVENT;
        event.title = "Médico";
        event.date = LocalDate.of(2026, 12, 2).toString();
        event.time = "09:00";
        event.remindDayBefore = true;
        event.notifiedDayBefore = true;
        desktop.add(event);
        desktop.save();
        desktop.enableSync();

        AgendaStore phone = store("reminder-phone");
        phone.enableSync();
        phone.mergeOperations(desktop.exportOperations());
        AgendaEntry phoneEvent = phone.all().get(0);
        phoneEvent.notifiedDayBefore = true;
        phone.save();

        event.date = LocalDate.of(2026, 12, 3).toString();
        desktop.save();
        phone.mergeOperations(desktop.exportOperations());

        assertEquals("2026-12-03", phone.all().get(0).date);
        assertFalse(phone.all().get(0).notifiedDayBefore,
                "Un cambio en fecha/hora debe habilitar el recordatorio local para la nueva fecha.");
    }

    private AgendaStore store(String name) {
        AgendaStore store = new AgendaStore(temporaryDirectory.resolve(name).resolve("agenda.json"));
        store.load();
        return store;
    }
}
