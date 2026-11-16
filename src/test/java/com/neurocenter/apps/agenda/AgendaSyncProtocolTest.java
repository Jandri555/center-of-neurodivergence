package com.neurocenter.apps.agenda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgendaSyncProtocolTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void clientAndServerExchangeOperationsBidirectionally() throws Exception {
        AgendaStore desktop = newStore("desktop");
        AgendaStore phone = newStore("phone");

        AgendaEntry desktopTask = task("Preparar presentación");
        desktop.add(desktopTask);
        desktop.save();
        desktop.enableSync();

        AgendaEntry phoneTask = task("Comprar cuaderno");
        phone.add(phoneTask);
        phone.save();
        phone.enableSync();

        PipedOutputStream clientOutput = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientOutput, 64 * 1024);
        PipedOutputStream serverOutput = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverOutput, 64 * 1024);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> server = executor.submit(() -> {
                AgendaSyncProtocol.runServer(serverInput, serverOutput, phone);
                return null;
            });

            AgendaSyncProtocol.runClient(clientInput, clientOutput, desktop);
            server.get(10, TimeUnit.SECONDS);

            assertEquals(2, desktop.all().size());
            assertEquals(2, phone.all().size());
            assertTrue(desktop.all().stream().anyMatch(e -> "Comprar cuaderno".equals(e.title)));
            assertTrue(phone.all().stream().anyMatch(e -> "Preparar presentación".equals(e.title)));

            // La misma operación puede volver a circular sin duplicar resultados.
            Future<?> secondServer = executor.submit(() -> {
                AgendaSyncProtocol.runServer(serverInput, serverOutput, phone);
                return null;
            });
            AgendaSyncProtocol.runClient(clientInput, clientOutput, desktop);
            secondServer.get(10, TimeUnit.SECONDS);
            assertEquals(2, desktop.all().size());
            assertEquals(2, phone.all().size());
        } finally {
            executor.shutdownNow();
            clientInput.close();
            clientOutput.close();
            serverInput.close();
            serverOutput.close();
        }
    }

    @Test
    void incompleteSyncFrameIsRejectedWithoutMutatingTheJournal() throws Exception {
        AgendaStore store = newStore("incomplete");
        AgendaEntry task = task("Cita");
        store.add(task);
        store.save();
        store.enableSync();

        byte[] invalidFrame = new byte[]{0, 0, 0, 10, '{', '}'};
        assertThrows(java.io.IOException.class, () ->
                AgendaSyncProtocol.runServer(
                        new java.io.ByteArrayInputStream(invalidFrame),
                        new java.io.ByteArrayOutputStream(),
                        store));
        assertEquals(1, store.all().size());
    }

    private AgendaStore newStore(String name) {
        AgendaStore store = new AgendaStore(temporaryDirectory.resolve(name).resolve("agenda.json"));
        store.load();
        return store;
    }

    private static AgendaEntry task(String title) {
        AgendaEntry task = new AgendaEntry();
        task.title = title;
        task.date = LocalDate.of(2026, 12, 5).toString();
        return task;
    }
}
