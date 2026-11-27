package com.neurocenter.core;

/**
 * Trusted host-process plugin services. The registry starts EMPTY.
 * A permission grant NEVER installs or activates a plugin.
 *
 * When the Android .ncp loader is implemented, it must register a provider
 * only after the plugin is installed and enabled, unregister it when disabled,
 * and never expose this registry to isolated .ncapp code.
 */
public final class AndroidPluginServices {
    private AndroidPluginServices() {}

    public interface AgendaBridge {
        String getAgendaJson();
        String addTask(String title, String dateSpanish, String timeIso, String notes);
        boolean setEntryCompleted(String entryId, boolean completed);
        boolean deleteAgendaEntry(String entryId);
    }

    private static volatile AgendaBridge agendaBridge;

    public static synchronized void registerAgendaBridge(AgendaBridge provider) {
        if (provider == null) throw new IllegalArgumentException("Provider missing");
        if (agendaBridge != null) throw new IllegalStateException("Agenda Bridge already registered");
        agendaBridge = provider;
    }

    public static synchronized void unregisterAgendaBridge(AgendaBridge provider) {
        if (provider == null || agendaBridge != provider)
            throw new IllegalArgumentException("Unrecognised Agenda Bridge provider");
        agendaBridge = null;
    }

    public static boolean isAgendaBridgeAvailable() {
        return agendaBridge != null;
    }

    public static AgendaBridge requireAgendaBridge() {
        AgendaBridge provider = agendaBridge;
        if (provider == null)
            throw new SecurityException("Esta función requiere el plugin Agenda Bridge instalado y habilitado.");
        return provider;
    }
}
