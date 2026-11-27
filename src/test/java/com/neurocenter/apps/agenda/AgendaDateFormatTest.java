package com.neurocenter.apps.agenda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgendaDateFormatTest {
    @TempDir Path directory;

    @Test
    void spanishFormatIsUsedForReadingWritingAndDisplay() {
        LocalDate day = LocalDate.of(2027, 2, 16);
        assertEquals("16/02/2027", AgendaDateFormat.store(day));
        assertEquals("16/02/2027", AgendaDateFormat.display(day));
        assertEquals(day, AgendaDateFormat.parse("16/02/2027"));
        assertEquals("16/02/2027", AgendaDateFormat.displayStored("16/02/2027"));
        assertEquals(day, AgendaDateFormat.fromPicker(2027, 1, 16));
        assertEquals("", AgendaDateFormat.displayStored(null));
    }

    @Test
    void invalidOrLegacyDatesAreRejectedWithoutMigration() {
        for (String invalid : List.of("2027-02-16", "16/2/2027", "31/02/2027",
                "29/02/2027", "2027-2-16", "15/13/2027")) {
            assertThrows(DateTimeParseException.class, () -> AgendaDateFormat.parse(invalid),
                    "El formato anterior no se migra: " + invalid);
        }
        assertEquals(LocalDate.of(2028, 2, 29), AgendaDateFormat.parse("29/02/2028"));
    }

    @Test
    void jsonSyncAndDeadlineOrderingUseSpanishDates() throws Exception {
        AgendaStore desktop = new AgendaStore(directory.resolve("desktop/agenda.json"));
        desktop.load();
        AgendaEntry later = new AgendaEntry();
        later.title = "Febrero";
        later.date = AgendaDateFormat.store(LocalDate.of(2027, 2, 16));
        desktop.add(later);
        AgendaEntry earlier = new AgendaEntry();
        earlier.title = "Diciembre";
        earlier.date = AgendaDateFormat.store(LocalDate.of(2026, 12, 3));
        desktop.add(earlier);
        desktop.save();

        String json = Files.readString(directory.resolve("desktop/agenda.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("16/02/2027"));
        assertTrue(json.contains("03/12/2026"));
        assertFalse(json.contains("2027-02-16"));
        assertEquals(List.of("Diciembre", "Febrero"),
                desktop.pendingTasks().stream().map(e -> e.title).toList());

        desktop.enableSync();
        AgendaStore mobile = new AgendaStore(directory.resolve("mobile/agenda.json"));
        mobile.load();
        mobile.enableSync();
        mobile.mergeOperations(desktop.exportOperations());
        assertEquals(List.of("03/12/2026", "16/02/2027"),
                mobile.pendingTasks().stream().map(e -> e.date).toList());
        assertEquals(LocalDate.of(2027, 2, 16), mobile.all().stream()
                .filter(e -> e.title.equals("Febrero")).findFirst().orElseThrow().localDate());
    }
}
