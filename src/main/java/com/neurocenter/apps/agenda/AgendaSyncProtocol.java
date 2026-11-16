package com.neurocenter.apps.agenda;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Protocolo bidireccional de sincronización independiente de USB o Bluetooth.
 *
 * <p>El iniciador envía sus operaciones; el receptor las guarda y responde
 * con su historial completo; el iniciador lo incorpora y confirma el reloj
 * causal resultante. El receptor solo confirma cuando ese reloj incluye
 * todas las operaciones que conoce.</p>
 *
 * <p>Los streams pertenecen al transporte y no se cierran aquí. Cada frame
 * utiliza una longitud de 32 bits seguida de JSON UTF-8.</p>
 */
final class AgendaSyncProtocol {

    static final int VERSION = 1;
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().create();

    private AgendaSyncProtocol() {}

    @FunctionalInterface
    interface OperationMerger {
        void merge(List<AgendaOperation> operations) throws IOException;
    }

    /**
     * Sincroniza desde el lado que inicia la conexión (por ejemplo, el PC).
     */
    static void runClient(InputStream input, OutputStream output, AgendaStore store) throws IOException {
        runClient(input, output, store, store::mergeOperations);
    }

    /**
     * Variante que permite al escritorio aplicar el lote remoto en el EDT,
     * mientras la conexión Bluetooth trabaja en un hilo auxiliar.
     */
    static void runClient(
            InputStream input,
            OutputStream output,
            AgendaStore store,
            OperationMerger merger) throws IOException {
        requireEnabled(store);

        SyncMessage request = message("SYNC_REQUEST", store);
        send(output, request);

        SyncMessage response = receive(input);
        requireKind(response, "SYNC_RESPONSE");
        merger.merge(response.operations);

        Map<String, Long> acknowledgedClock = store.syncVectorClock();
        SyncMessage ack = new SyncMessage();
        ack.kind = "SYNC_ACK";
        ack.protocolVersion = VERSION;
        ack.vectorClock = acknowledgedClock;
        send(output, ack);

        SyncMessage complete = receive(input);
        requireKind(complete, "SYNC_COMPLETE");
        if (!covers(complete.vectorClock, acknowledgedClock)) {
            throw new IOException("El otro dispositivo no ha confirmado todas las operaciones recibidas.");
        }
    }

    /**
     * Sincroniza desde el lado que espera una conexión (por ejemplo, Android).
     */
    static void runServer(InputStream input, OutputStream output, AgendaStore store) throws IOException {
        runServer(input, output, store, store::mergeOperations);
    }

    /**
     * Variante que permite aplicar las operaciones en el hilo propietario de la agenda.
     */
    static void runServer(
            InputStream input,
            OutputStream output,
            AgendaStore store,
            OperationMerger merger) throws IOException {
        requireEnabled(store);

        SyncMessage request = receive(input);
        requireKind(request, "SYNC_REQUEST");
        merger.merge(request.operations);

        // La respuesta ya incluye tanto el historial propio como el recibido.
        send(output, message("SYNC_RESPONSE", store));

        SyncMessage ack = receive(input);
        requireKind(ack, "SYNC_ACK");
        Map<String, Long> localClock = store.syncVectorClock();
        if (!covers(ack.vectorClock, localClock)) {
            throw new IOException("La confirmación del iniciador no cubre todo el historial del receptor.");
        }

        SyncMessage complete = new SyncMessage();
        complete.kind = "SYNC_COMPLETE";
        complete.protocolVersion = VERSION;
        complete.vectorClock = localClock;
        send(output, complete);
    }

    /**
     * Mantiene una sesión iniciada por el escritorio. Tras el intercambio inicial,
     * ambos extremos envían periódicamente solo las operaciones que el otro aún
     * no conoce. La sesión termina al cerrar el transporte o solicitar la salida.
     */
    static void runClientSession(
            InputStream input,
            OutputStream output,
            AgendaStore store,
            OperationMerger merger,
            BooleanSupplier keepAlive,
            Runnable onConnected) throws IOException {
        runClient(input, output, store, merger);
        runContinuousSession(input, output, store, merger, keepAlive, onConnected);
    }

    /** Variante persistente del extremo Android que espera la conexión. */
    static void runServerSession(
            InputStream input,
            OutputStream output,
            AgendaStore store,
            OperationMerger merger,
            BooleanSupplier keepAlive,
            Runnable onConnected) throws IOException {
        runServer(input, output, store, merger);
        runContinuousSession(input, output, store, merger, keepAlive, onConnected);
    }

    private static void runContinuousSession(
            InputStream input,
            OutputStream output,
            AgendaStore store,
            OperationMerger merger,
            BooleanSupplier keepAlive,
            Runnable onConnected) throws IOException {
        if (keepAlive == null || !keepAlive.getAsBoolean()) return;
        if (onConnected != null) onConnected.run();

        // El intercambio inicial ya ha dejado ambos dispositivos con el mismo
        // reloj causal. En cada ronda se anuncia el reloj y se envía lo que falta.
        Map<String, Long> remoteClock = new LinkedHashMap<>(store.syncVectorClock());
        while (keepAlive.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            SyncMessage update = new SyncMessage();
            update.kind = "SESSION_UPDATE";
            update.protocolVersion = VERSION;
            update.vectorClock = store.syncVectorClock();
            update.operations = store.exportOperationsAfter(remoteClock);
            send(output, update);

            SyncMessage incoming = receive(input);
            requireKind(incoming, "SESSION_UPDATE");
            if (!incoming.operations.isEmpty()) {
                merger.merge(incoming.operations);
            }
            // Este reloj representa lo que el otro extremo conocía al enviar
            // su lote; las operaciones que acaba de recibir aparecerán en la
            // siguiente ronda, evitando perder confirmaciones simultáneas.
            remoteClock = new LinkedHashMap<>(incoming.vectorClock);

            try {
                Thread.sleep(350L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (keepAlive.getAsBoolean()) {
                    throw new IOException("Se interrumpió la sesión de sincronización.", ex);
                }
                break;
            }
        }
    }

    private static SyncMessage message(String kind, AgendaStore store) {
        SyncMessage message = new SyncMessage();
        message.kind = kind;
        message.protocolVersion = VERSION;
        message.operations = store.exportOperations();
        message.vectorClock = store.syncVectorClock();
        return message;
    }

    private static void requireEnabled(AgendaStore store) throws IOException {
        if (store == null || !store.isSyncEnabled()) {
            throw new IOException("Activa la sincronización de la agenda en ambos dispositivos antes de conectar.");
        }
    }

    private static void send(OutputStream output, SyncMessage message) throws IOException {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length < 1 || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("El lote de sincronización supera el tamaño máximo permitido.");
        }

        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    private static SyncMessage receive(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        final int length;
        try {
            length = data.readInt();
        } catch (EOFException ex) {
            throw new IOException("La conexión se cerró antes de completar la sincronización.", ex);
        }

        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new IOException("Tamaño de mensaje de sincronización no válido.");
        }

        byte[] payload = new byte[length];
        data.readFully(payload);

        try {
            SyncMessage message = GSON.fromJson(
                    new String(payload, StandardCharsets.UTF_8), SyncMessage.class);
            if (message == null || message.protocolVersion != VERSION || message.kind == null) {
                throw new IOException("Mensaje de sincronización incompatible o incompleto.");
            }
            if (message.vectorClock == null) message.vectorClock = new LinkedHashMap<>();
            if (message.operations == null) message.operations = new ArrayList<>();
            return message;
        } catch (JsonParseException ex) {
            throw new IOException("El mensaje de sincronización contiene JSON no válido.", ex);
        }
    }

    private static void requireKind(SyncMessage message, String expected) throws IOException {
        if (!expected.equals(message.kind)) {
            throw new IOException("Se esperaba " + expected + " y se recibió " + message.kind + ".");
        }
    }

    private static boolean covers(Map<String, Long> received, Map<String, Long> required) {
        if (received == null || required == null) return false;
        for (Map.Entry<String, Long> entry : required.entrySet()) {
            Long actual = received.get(entry.getKey());
            if (actual == null || actual < entry.getValue()) return false;
        }
        return true;
    }

    private static final class SyncMessage {
        String kind;
        int protocolVersion;
        List<AgendaOperation> operations = new ArrayList<>();
        Map<String, Long> vectorClock = new LinkedHashMap<>();
    }
}
