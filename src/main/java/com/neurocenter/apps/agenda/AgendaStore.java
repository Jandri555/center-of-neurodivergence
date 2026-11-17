package com.neurocenter.apps.agenda;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guarda y recupera las entradas de la agenda en JSON.
 *
 * Cuando se activa la sincronización, cada cambio se registra además como una
 * operación inmutable en agenda-operations.jsonl. El JSON sigue siendo una
 * copia materializada para que la agenda de escritorio conserve su arranque simple.
 */
final class AgendaStore {

    private static final Type LIST_TYPE = new TypeToken<List<AgendaEntry>>() {}.getType();
    private static final Comparator<AgendaEntry> ORDER =
            Comparator.comparing((AgendaEntry e) -> e.time == null ? "" : e.time)
                    .thenComparing(e -> e.title == null ? "" : e.title.toLowerCase());

    /** Por fecha límite (las que no tienen hora, al final de su día) y luego por título. */
    private static final Comparator<AgendaEntry> BY_DEADLINE =
            Comparator.comparing((AgendaEntry e) -> e.localDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(e -> e.localTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(e -> e.title == null ? "" : e.title.toLowerCase());

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<AgendaEntry> entries = new ArrayList<>();
    private final AgendaSyncJournal syncJournal;
    private Map<String, String> lastSavedState = new LinkedHashMap<>();

    AgendaStore(Path file) {
        this(file, null);
    }

    AgendaStore(Path file, String deviceName) {
        this.file = file;
        try {
            this.syncJournal = deviceName == null || deviceName.trim().isEmpty()
                    ? new AgendaSyncJournal(file)
                    : new AgendaSyncJournal(file, deviceName);
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo abrir el historial de sincronización de la agenda.", ex);
        }
    }

    synchronized void load() {
        entries.clear();
        if (Files.isRegularFile(file)) {
            try {
                List<AgendaEntry> loaded = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) entries.addAll(loaded);
            } catch (JsonParseException | IOException e) {
                // Archivo dañado: lo apartamos (no se pierde nada) y empezamos con la agenda vacía.
                try {
                    Files.move(file, file.resolveSibling("agenda.corrupto-" + System.currentTimeMillis() + ".json"));
                } catch (IOException ignored) {
                    // si ni siquiera se puede apartar, seguimos igualmente
                }
            }
        }

        if (syncJournal.isEnabled()) {
            // Solo completa una línea base local que se interrumpió durante la activación.
            if (syncJournal.needsBaseline()) syncJournal.enable(entries);
            if (!syncJournal.exportOperations().isEmpty()) {
                Map<String, AgendaEntry> localEntries = new LinkedHashMap<>();
                for (AgendaEntry entry : entries) localEntries.put(entry.id, entry);
                List<AgendaEntry> reconstructed = syncJournal.materialize();
                for (AgendaEntry entry : reconstructed) {
                    AgendaEntry local = localEntries.get(entry.id);
                    if (local != null) copyLocalReminderState(local, entry);
                }
                entries.clear();
                entries.addAll(reconstructed);
                writeSnapshot();
            }
        }
        rememberSavedState();
    }

    /** Escritura segura: primero a un archivo temporal y luego se sustituye el original. */
    synchronized void save() {
        if (syncJournal.isEnabled()) recordChanges();
        writeSnapshot();
        rememberSavedState();
    }

    private void recordChanges() {
        Map<String, AgendaEntry> current = new LinkedHashMap<>();
        for (AgendaEntry entry : entries) {
            if (entry.id != null) current.put(entry.id, entry);
        }

        for (Map.Entry<String, AgendaEntry> item : current.entrySet()) {
            String previousJson = lastSavedState.get(item.getKey());
            String currentJson = gson.toJson(item.getValue().syncCopy());
            if (!currentJson.equals(previousJson)) syncJournal.recordUpsert(item.getValue());
        }
        for (String previousId : lastSavedState.keySet()) {
            if (!current.containsKey(previousId)) syncJournal.recordDelete(previousId);
        }
    }

    private void rememberSavedState() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (AgendaEntry entry : entries) {
            if (entry.id != null) snapshot.put(entry.id, gson.toJson(entry.syncCopy()));
        }
        lastSavedState = snapshot;
    }

    /** Los avisos ya mostrados son locales: cada dispositivo los gestiona por separado. */
    private static void copyLocalReminderState(AgendaEntry source, AgendaEntry target) {
        boolean sameSchedule = java.util.Objects.equals(source.type, target.type)
                && java.util.Objects.equals(source.date, target.date)
                && java.util.Objects.equals(source.time, target.time)
                && source.remindDayBefore == target.remindDayBefore
                && source.remindCustom == target.remindCustom
                && source.remindBeforeMinutes == target.remindBeforeMinutes;
        if (!sameSchedule) return;

        target.remind = source.remind;
        target.notified = source.notified;
        target.notifiedDayBefore = source.notifiedDayBefore;
        target.notifiedCustom = source.notifiedCustom;
    }

    private void writeSnapshot() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, gson.toJson(entries, LIST_TYPE).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Activa la captura de operaciones y crea una línea base de la agenda existente. */
    synchronized void enableSync() {
        syncJournal.enable(entries);
        rememberSavedState();
    }

    synchronized boolean isSyncEnabled() {
        return syncJournal.isEnabled();
    }

    synchronized String syncDeviceId() {
        return syncJournal.deviceId();
    }

    synchronized String syncDeviceName() {
        return syncJournal.deviceName();
    }

    /** Registra el reloj de confirmación individual de un equipo vinculado. */
    synchronized void acknowledgePeer(String peerId, String peerName, Map<String, Long> peerClock) {
        syncJournal.acknowledgePeer(peerId, peerName, peerClock);
    }

    /** Equipos conocidos y operaciones que cada uno todavía tiene pendientes. */
    synchronized List<AgendaSyncJournal.PeerInfo> linkedPeers() {
        return syncJournal.linkedPeers();
    }

    synchronized int pendingOperationsForPeer(String peerId) {
        return syncJournal.pendingOperationsForPeer(peerId);
    }

    /** Estado causal que se comparte durante el protocolo de sincronización. */
    synchronized Map<String, Long> syncVectorClock() {
        return syncJournal.vectorClock();
    }

    /** Historial completo, listo para intercambiar por USB o Bluetooth. */
    synchronized List<AgendaOperation> exportOperations() {
        return syncJournal.exportOperations();
    }

    /** Exporta operaciones posteriores al reloj causal conocido por el otro dispositivo. */
    synchronized List<AgendaOperation> exportOperationsAfter(Map<String, Long> remoteClock) {
        return syncJournal.exportOperationsAfter(remoteClock);
    }

    /** Incorpora operaciones remotas y reconstruye el estado sin generar operaciones locales duplicadas. */
    synchronized void mergeOperations(List<AgendaOperation> incoming) {
        if (!syncJournal.isEnabled() || syncJournal.needsBaseline()) {
            throw new IllegalStateException("Activa la sincronización y completa la línea base antes de recibir operaciones.");
        }
        Map<String, AgendaEntry> localEntries = new LinkedHashMap<>();
        for (AgendaEntry entry : entries) localEntries.put(entry.id, entry);
        syncJournal.mergeOperations(incoming);
        List<AgendaEntry> reconstructed = syncJournal.materialize();
        for (AgendaEntry entry : reconstructed) {
            AgendaEntry local = localEntries.get(entry.id);
            if (local != null) copyLocalReminderState(local, entry);
        }
        entries.clear();
        entries.addAll(reconstructed);
        writeSnapshot();
        rememberSavedState();
    }

    synchronized List<AgendaEntry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    synchronized List<AgendaEntry> on(LocalDate day) {
        List<AgendaEntry> result = new ArrayList<>();
        for (AgendaEntry entry : entries) {
            if (day.equals(entry.localDate())) result.add(entry);
        }
        result.sort(ORDER);
        return Collections.unmodifiableList(result);
    }

    /** Todas las tareas sin hacer, la de fecha límite más cercana primero. */
    synchronized List<AgendaEntry> pendingTasks() {
        List<AgendaEntry> result = new ArrayList<>();
        for (AgendaEntry entry : entries) {
            if (!entry.isEvent() && !entry.done) result.add(entry);
        }
        result.sort(BY_DEADLINE);
        return Collections.unmodifiableList(result);
    }

    synchronized void add(AgendaEntry entry) {
        entries.add(entry);
    }

    synchronized void replace(AgendaEntry existing, AgendaEntry updated) {
        int index = entries.indexOf(existing);
        if (index >= 0) entries.set(index, updated);
    }

    synchronized void remove(AgendaEntry entry) {
        entries.remove(entry);
    }
}
