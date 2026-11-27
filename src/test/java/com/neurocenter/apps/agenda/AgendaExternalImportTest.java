package com.neurocenter.apps.agenda;

import com.neurocenter.api.services.ExternalTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgendaExternalImportTest {
    @TempDir Path tempDir;

    @Test
    void repeatedImportsUpdateTheSameMoodleTaskInsteadOfDuplicatingIt() {
        AgendaStore store = new AgendaStore(tempDir.resolve("agenda.json"));
        store.load();

        ExternalTask first = new ExternalTask("assignment-42", "Entrega inicial",
                LocalDate.of(2026, 12, 1), LocalTime.of(23, 59), "Primera descripción", false);
        assertEquals(1, store.importExternalTasks("teis-aula-virtual", List.of(first)));
        assertEquals(1, store.all().size());

        ExternalTask updated = new ExternalTask("assignment-42", "Entrega modificada",
                LocalDate.of(2026, 12, 3), LocalTime.of(23, 59), "Fecha ampliada", false);
        assertEquals(1, store.importExternalTasks("teis-aula-virtual", List.of(updated)));

        assertEquals(1, store.all().size());
        assertEquals("Entrega modificada", store.all().get(0).title);
        assertEquals("03/12/2026", store.all().get(0).date);
    }

    @Test
    void differentSourcesKeepTheirTasksSeparate() {
        AgendaStore store = new AgendaStore(tempDir.resolve("agenda.json"));
        store.load();
        ExternalTask task = new ExternalTask("42", "Tarea", null, null, "", false);

        store.importExternalTasks("source-a", List.of(task));
        store.importExternalTasks("source-b", List.of(task));

        assertEquals(2, store.all().size());
    }
}
