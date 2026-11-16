package com.neurocenter.apps.agenda;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operación inmutable del historial de sincronización de la agenda.
 * Cada operación contiene el estado completo de una entrada o su tombstone.
 */
final class AgendaOperation {

    static final String UPSERT = "UPSERT";
    static final String DELETE = "DELETE";

    String operationId;
    String deviceId;
    long counter;
    long lamport;
    String type;
    String entryId;
    AgendaEntry entry;
    Map<String, Long> vectorClock = new LinkedHashMap<>();

    AgendaOperation() {}

    AgendaOperation(String operationId, String deviceId, long counter, long lamport,
                    String type, String entryId, AgendaEntry entry, Map<String, Long> vectorClock) {
        this.operationId = operationId;
        this.deviceId = deviceId;
        this.counter = counter;
        this.lamport = lamport;
        this.type = type;
        this.entryId = entryId;
        this.entry = entry == null ? null : entry.syncCopy();
        this.vectorClock = new LinkedHashMap<>(vectorClock);
    }

    boolean isValid() {
        if (operationId == null || deviceId == null || deviceId.isBlank()
                || counter < 1 || lamport < 1 || entryId == null || entryId.isBlank()
                || vectorClock == null) return false;
        if (!operationId.equals(deviceId + ":" + counter)) return false;
        if (!Long.valueOf(counter).equals(vectorClock.get(deviceId))) return false;
        if (vectorClock.values().stream().anyMatch(value -> value == null || value < 0)) return false;
        if (UPSERT.equals(type)) return entry != null && entryId.equals(entry.id);
        return DELETE.equals(type) && entry == null;
    }
}
