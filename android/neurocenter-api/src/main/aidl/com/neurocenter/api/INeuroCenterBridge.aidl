package com.neurocenter.api;

/**
 * IPC surface for trusted NeuroCenter services.
 *
 * Implementations MUST identify the caller from Binder.getCallingUid() and must
 * authorize every operation server-side. App-supplied package names are never identity.
 */
interface INeuroCenterBridge {
    String getAppId();
    boolean hasCapability(String capabilityId);

    /** Requires agenda.read. Returns a JSON snapshot, never a mutable host object. */
    String getAgendaJson();

    /** Requires agenda.write. dateSpanish uses dd/MM/aaaa; timeIso uses HH:mm. */
    String addTask(String title, String dateSpanish, String timeIso, String notes);

    /** Requires agenda.write. */
    boolean setEntryCompleted(String entryId, boolean completed);

    /** Requires agenda.write. */
    boolean deleteAgendaEntry(String entryId);

    /** Requires credentials.access. */
    void saveCredential(String key, in char[] secret);

    /** Requires credentials.access. Returns null if the key does not exist. */
    char[] loadCredential(String key);

    /** Requires credentials.access. */
    void deleteCredential(String key);
}
