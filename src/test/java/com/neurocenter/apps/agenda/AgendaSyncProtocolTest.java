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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AgendaSyncProtocolTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void clientAndServerExchangeOperationsBidirectionally() throws Exception {
        AgendaStore desktop = newStore("desktop");
        AgendaStore phone = newStore("phone");
        desktop.enableSync();
        phone.enableSync();

        AgendaEntry desktopTask = task("Preparar presentación");
        desktop.add(desktopTask);
        desktop.save();

        AgendaEntry phoneTask = task("Comprar cuaderno");
        phone.add(phoneTask);
        phone.save();

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
    void persistentSessionSynchronizesNewEntriesAutomaticallyInBothDirections() throws Exception {
        AgendaStore desktop = newStore("desktop-live");
        AgendaStore phone = newStore("phone-live");
        desktop.enableSync();
        phone.enableSync();

        PipedOutputStream clientOutput = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientOutput, 64 * 1024);
        PipedOutputStream serverOutput = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverOutput, 64 * 1024);

        AtomicBoolean connected = new AtomicBoolean(true);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> server = executor.submit(() -> {
                AgendaSyncProtocol.runServerSession(
                        serverInput, serverOutput, phone, phone::mergeOperations,
                        connected::get, ready::countDown);
                return null;
            });
            Future<?> client = executor.submit(() -> {
                AgendaSyncProtocol.runClientSession(
                        clientInput, clientOutput, desktop, desktop::mergeOperations,
                        connected::get, ready::countDown);
                return null;
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both devices should complete the initial handshake");

            AgendaEntry desktopEntry = task("Tarea creada en el PC");
            desktop.add(desktopEntry);
            desktop.save();
            await(() -> phone.all().size() == 1, 5_000);

            AgendaEntry phoneEntry = task("Tarea creada en Android");
            phone.add(phoneEntry);
            phone.save();
            await(() -> desktop.all().size() == 2, 5_000);

            assertTrue(phone.all().stream().anyMatch(e -> "Tarea creada en el PC".equals(e.title)));
            assertTrue(desktop.all().stream().anyMatch(e -> "Tarea creada en Android".equals(e.title)));
            assertFalse(client.isDone(), "The desktop session should remain connected");
            assertFalse(server.isDone(), "The Android session should remain connected");

            connected.set(false);
            client.get(5, TimeUnit.SECONDS);
            server.get(5, TimeUnit.SECONDS);
        } finally {
            connected.set(false);
            executor.shutdownNow();
            clientInput.close();
            clientOutput.close();
            serverInput.close();
            serverOutput.close();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for automatic synchronization");
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
