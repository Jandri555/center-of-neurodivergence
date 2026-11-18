package com.neurocenter.api.agenda;

/** Summary returned after importing one batch of external tasks. */
public record AgendaImportResult(int created, int updated, int unchanged) {
    public AgendaImportResult {
        if (created < 0 || updated < 0 || unchanged < 0) {
            throw new IllegalArgumentException("Los contadores de importación no pueden ser negativos.");
        }
    }

    public int total() {
        return created + updated + unchanged;
    }

    public boolean changed() {
        return created > 0 || updated > 0;
    }
}
