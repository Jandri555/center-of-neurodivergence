package com.neurocenter.api.agenda;

import java.util.List;

/**
 * Public, read/write API for the shared NeuroCenter agenda.
 *
 * <p>Implementations are thread-safe. External apps should call this service from
 * their worker thread, not from Swing's event-dispatch thread.</p>
 */
public interface AgendaService {

    /**
     * Inserts or updates tasks belonging to one external source.
     *
     * <p>The pair {@code (sourceId, ExternalTask.externalId)} is the stable identity.
     * Calling this method repeatedly with the same data is harmless: existing entries
     * are updated rather than duplicated. The completion state chosen by the user is
     * preserved when source data changes.</p>
     *
     * <p>Only tasks included in this batch are inserted or updated. Tasks missing from
     * the batch are deliberately left untouched; callers must not interpret an
     * incomplete response as a request to delete agenda entries.</p>
     *
     * @param sourceId stable identifier for the source, e.g.
     *                 {@code moodle:centros.edu.xunta.gal/iesteis/aulavirtual}
     * @param tasks complete snapshot of tasks returned by the source
     * @return number of created, updated and unchanged tasks
     */
    AgendaImportResult upsertExternalTasks(String sourceId, List<ExternalTask> tasks);
}
