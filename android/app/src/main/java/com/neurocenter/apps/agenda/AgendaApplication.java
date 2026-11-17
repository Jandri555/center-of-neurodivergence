package com.neurocenter.apps.agenda;

import android.app.Application;

/**
 * Mantiene una única instancia de AgendaStore para la pantalla y el servicio
 * Bluetooth. Así ambos usan el mismo estado y no pisan escrituras cuando la
 * sincronización continúa sin la Activity.
 */
public final class AgendaApplication extends Application {

    private AgendaStore agendaStore;

    synchronized AgendaStore getAgendaStore() {
        if (agendaStore == null) {
            agendaStore = new AgendaStore(getFilesDir().toPath().resolve("agenda.json"));
            agendaStore.load();
        }
        return agendaStore;
    }
}
