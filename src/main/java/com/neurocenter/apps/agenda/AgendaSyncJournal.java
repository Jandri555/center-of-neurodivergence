package com.neurocenter.apps.agenda;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Historial append-only de operaciones de Agenda.
 *
 * <p>El transporte (USB/Bluetooth) solo intercambia operaciones. Este componente
 * se encarga de deduplicarlas, mantener el reloj causal y reconstruir el estado.</p>
 *
 * <p>Las operaciones se conservan en el dispositivo para que una sincronización
 * interrumpida pueda reintentarse. Más adelante podrán compactarse cuando todos
 * los dispositivos emparejados confirmen que las han recibido.</p>
 */
final class AgendaSyncJournal {

    private static final Comparator<AgendaOperation> ORDER =
            Comparator.comparingLong((AgendaOperation op) -> op.lamport)
                    .thenComparing(op -> op.deviceId)
                    .thenComparingLong(op -> op.counter);

    private final Path operationsFile;
    private final Path metadataFile;
    private final Gson gson = new GsonBuilder().create();
    private final List<AgendaOperation> operations = new ArrayList<>();
    private final Set<String> knownOperationIds = new LinkedHashSet<>();
    private final Metadata metadata;

    private static final class Metadata {
        String deviceId = UUID.randomUUID().toString();
        boolean enabled;
        long lamport;
        Map<String, Long> vectorClock = new LinkedHashMap<>();
    }

    AgendaSyncJournal(Path agendaFile) throws IOException {
        Path parent = agendaFile.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("No se pudo resolver la carpeta de la agenda.");
        this.operationsFile = parent.resolve("agenda-operations.jsonl");
        this.metadataFile = parent.resolve("agenda-sync.json");
        this.metadata = loadMetadata();
        loadOperations();
        saveMetadata();
    }

    synchronized boolean isEnabled() {
        return metadata.enabled;
    }

    /**
     * Activa el historial y crea una línea base con las entradas que ya existían.
     * No altera ni borra el archivo de agenda existente.
     */
    synchronized void enable(List<AgendaEntry> currentEntries) {
        if (metadata.enabled) return;
        metadata.enabled = true;
        saveMetadataUnchecked();
        if (operations.isEmpty()) {
            for (AgendaEntry entry : currentEntries) recordUpsert(entry);
        }
        saveMetadataUnchecked();
    }

    synchronized void recordUpsert(AgendaEntry entry) {
        if (!metadata.enabled || entry == null || entry.id == null || entry.id.isBlank()) return;
        appendNewOperation(AgendaOperation.UPSERT, entry.id, entry);
    }

    synchronized void recordDelete(String entryId) {
        if (!metadata.enabled || entryId == null || entryId.isBlank()) return;
        appendNewOperation(AgendaOperation.DELETE, entryId, null);
    }

    private void appendNewOperation(String type, String entryId, AgendaEntry entry) {
        long counter = metadata.vectorClock.getOrDefault(metadata.deviceId, 0L) + 1L;
        long lamport = metadata.lamport + 1L;
        Map<String, Long> clock = new LinkedHashMap<>(metadata.vectorClock);
        clock.put(metadata.deviceId, counter);

        AgendaOperation operation = new AgendaOperation(
                metadata.deviceId + ":" + counter,
                metadata.deviceId,
                counter,
                lamport,
                type,
                entryId,
                entry,
                clock);

        append(operation);
        operations.add(operation);
        knownOperationIds.add(operation.operationId);
        metadata.vectorClock = clock;
        metadata.lamport = lamport;
        saveMetadataUnchecked();
    }

    /** Devuelve el historial completo para que el intercambio pueda reintentarse. */
    synchronized List<AgendaOperation> exportOperations() {
        List<AgendaOperation> copy = new ArrayList<>(operations.size());
        for (AgendaOperation operation : operations) {
            copy.add(gson.fromJson(gson.toJson(operation), AgendaOperation.class));
        }
        return List.copyOf(copy);
    }

    /**
     * Incorpora operaciones de otro dispositivo. Es idempotente: repetir un lote
     * no duplica órdenes ni cambia el resultado.
     */
    synchronized void mergeOperations(List<AgendaOperation> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        boolean changed = false;
        for (AgendaOperation operation : incoming) {
            if (operation == null || !operation.isValid()
                    || knownOperationIds.contains(operation.operationId)) continue;
            append(operation);
            operations.add(operation);
            knownOperationIds.add(operation.operationId);
            mergeClock(operation.vectorClock);
            metadata.vectorClock.merge(operation.deviceId, operation.counter, Math::max);
            metadata.lamport = Math.max(metadata.lamport, operation.lamport);
            changed = true;
        }
        if (changed) saveMetadataUnchecked();
    }

    synchronized List<AgendaEntry> materialize() {
        Map<String, List<AgendaOperation>> byEntry = new LinkedHashMap<>();
        for (AgendaOperation operation : operations) {
            byEntry.computeIfAbsent(operation.entryId, ignored -> new ArrayList<>()).add(operation);
        }

        List<AgendaEntry> result = new ArrayList<>();
        for (List<AgendaOperation> history : byEntry.values()) {
            List<AgendaOperation> heads = history.stream()
                    .filter(candidate -> history.stream().noneMatch(other ->
                            other != candidate && happensBefore(candidate, other)))
                    .toList();

            // Si dos dispositivos borran/actualizan la misma entrada sin haberse
            // sincronizado, el borrado concurrente gana para no resucitar datos.
            List<AgendaOperation> deletions = heads.stream()
                    .filter(op -> AgendaOperation.DELETE.equals(op.type))
                    .toList();
            AgendaOperation winner = (deletions.isEmpty() ? heads : deletions)
                    .stream().max(ORDER).orElse(null);

            if (winner != null && AgendaOperation.UPSERT.equals(winner.type)) {
                result.add(winner.entry.copy());
            }
        }
        return result;
    }

    synchronized Map<String, Long> vectorClock() {
        return Map.copyOf(metadata.vectorClock);
    }

    private void mergeClock(Map<String, Long> incoming) {
        if (incoming == null) return;
        incoming.forEach((device, counter) -> {
            if (device != null && counter != null && counter >= 0) {
                metadata.vectorClock.merge(device, counter, Math::max);
            }
        });
    }

    /** true si every event known by before is causally included in after. */
    private static boolean happensBefore(AgendaOperation before, AgendaOperation after) {
        boolean strictlyEarlier = false;
        Set<String> devices = new LinkedHashSet<>(before.vectorClock.keySet());
        devices.addAll(after.vectorClock.keySet());
        for (String device : devices) {
            long a = before.vectorClock.getOrDefault(device, 0L);
            long b = after.vectorClock.getOrDefault(device, 0L);
            if (a > b) return false;
            if (a < b) strictlyEarlier = true;
        }
        return strictlyEarlier;
    }

    private Metadata loadMetadata() throws IOException {
        if (!Files.isRegularFile(metadataFile)) return new Metadata();
        try {
            Metadata loaded = gson.fromJson(Files.readString(metadataFile, StandardCharsets.UTF_8), Metadata.class);
            if (loaded == null) throw new IOException("El archivo de sincronización está vacío.");
            if (loaded.deviceId == null || loaded.deviceId.isBlank()) loaded.deviceId = UUID.randomUUID().toString();
            if (loaded.vectorClock == null) loaded.vectorClock = new LinkedHashMap<>();
            return loaded;
        } catch (JsonParseException ex) {
            throw new IOException("El archivo de configuración de sincronización está dañado.", ex);
        }
    }

    private void loadOperations() throws IOException {
        if (!Files.isRegularFile(operationsFile)) return;
        String raw = Files.readString(operationsFile, StandardCharsets.UTF_8);
        String[] lines = raw.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            try {
                AgendaOperation operation = gson.fromJson(line, AgendaOperation.class);
                if (operation == null || !operation.isValid()) {
                    throw new IOException("Operación inválida en la línea " + (i + 1) + ".");
                }
                if (knownOperationIds.add(operation.operationId)) {
                    operations.add(operation);
                    mergeClock(operation.vectorClock);
                    metadata.vectorClock.merge(operation.deviceId, operation.counter, Math::max);
                    metadata.lamport = Math.max(metadata.lamport, operation.lamport);
                }
            } catch (JsonParseException ex) {
                throw new IOException("Historial de sincronización dañado en la línea " + (i + 1) + ".", ex);
            }
        }
    }

    private void append(AgendaOperation operation) {
        try {
            Files.createDirectories(operationsFile.getParent());
            byte[] bytes = (gson.toJson(operation) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(operationsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo escribir el historial de sincronización.", ex);
        }
    }

    private void saveMetadataUnchecked() {
        try {
            saveMetadata();
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo guardar el estado de sincronización.", ex);
        }
    }

    private void saveMetadata() throws IOException {
        Files.createDirectories(metadataFile.getParent());
        Path temporary = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(metadata), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, metadataFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
