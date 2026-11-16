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
        boolean baselineComplete;
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
    synchronized boolean needsBaseline() {
        return metadata.enabled && !metadata.baselineComplete;
    }

    synchronized void enable(List<AgendaEntry> currentEntries) {
        if (!metadata.enabled) {
            metadata.enabled = true;
            metadata.baselineComplete = false;
            saveMetadataUnchecked();
        }
        if (metadata.baselineComplete) return;

        // The baseline only captures entries that existed on this device when
        // sync was enabled. Imported remote entries must never become local writes.
        for (AgendaEntry entry : currentEntries) {
            if (entry == null || entry.id == null || entry.id.trim().isEmpty()) continue;
            boolean alreadySeeded = operations.stream().anyMatch(operation ->
                    metadata.deviceId.equals(operation.deviceId)
                            && AgendaOperation.UPSERT.equals(operation.type)
                            && entry.id.equals(operation.entryId));
            if (!alreadySeeded) recordUpsert(entry);
        }
        metadata.baselineComplete = true;
        saveMetadataUnchecked();
    }

    synchronized void recordUpsert(AgendaEntry entry) {
        if (!metadata.enabled || entry == null || entry.id == null || entry.id.trim().isEmpty()) return;
        appendNewOperation(AgendaOperation.UPSERT, entry.id, entry);
    }

    synchronized void recordDelete(String entryId) {
        if (!metadata.enabled || entryId == null || entryId.trim().isEmpty()) return;
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
        return java.util.Collections.unmodifiableList(copy);
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
                    .collect(java.util.stream.Collectors.toList());

            // Si dos dispositivos borran/actualizan la misma entrada sin haberse
            // sincronizado, el borrado concurrente gana para no resucitar datos.
            List<AgendaOperation> deletions = new ArrayList<>();
            for (AgendaOperation operation : heads) {
                if (AgendaOperation.DELETE.equals(operation.type)) deletions.add(operation);
            }
            AgendaOperation winner = (deletions.isEmpty() ? heads : deletions)
                    .stream().max(ORDER).orElse(null);

            if (winner != null && AgendaOperation.UPSERT.equals(winner.type)) {
                result.add(winner.entry.copy());
            }
        }
        return result;
    }

    synchronized Map<String, Long> vectorClock() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata.vectorClock));
    }

    private void mergeClock(Map<String, Long> incoming) {
        if (incoming == null) return;
        incoming.forEach((device, counter) -> {
            if (device != null && counter != null && counter >= 0) {
                metadata.vectorClock.merge(device, counter, Math::max);
            }
        });
    }

    /** Devuelve true si todos los cambios conocidos por before están incluidos en after. */
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
            Metadata loaded = gson.fromJson(new String(Files.readAllBytes(metadataFile), StandardCharsets.UTF_8), Metadata.class);
            if (loaded == null) throw new IOException("El archivo de sincronización está vacío.");
            if (loaded.deviceId == null || loaded.deviceId.trim().isEmpty()) loaded.deviceId = UUID.randomUUID().toString();
            if (loaded.vectorClock == null) loaded.vectorClock = new LinkedHashMap<>();
            return loaded;
        } catch (JsonParseException ex) {
            throw new IOException("El archivo de configuración de sincronización está dañado.", ex);
        }
    }

    private void loadOperations() throws IOException {
        if (!Files.isRegularFile(operationsFile)) return;
        String raw = new String(Files.readAllBytes(operationsFile), StandardCharsets.UTF_8);
        // Una interrupción durante el último append puede dejar una línea final
        // incompleta. Solo se descarta ese fragmento; las líneas confirmadas se conservan.
        if (!raw.isEmpty() && !raw.endsWith("\n") && !raw.endsWith("\r")) {
            int lastBreak = Math.max(raw.lastIndexOf('\n'), raw.lastIndexOf('\r'));
            raw = lastBreak >= 0 ? raw.substring(0, lastBreak + 1) : "";
            Files.write(operationsFile, raw.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
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
        Files.write(temporary, gson.toJson(metadata).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, metadataFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
