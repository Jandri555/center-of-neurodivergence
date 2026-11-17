package com.neurocenter.apps.agenda;

import android.app.Application;

import com.neurocenter.core.AppPaths;

/**
 * Mantiene una única instancia de AgendaStore para la pantalla y el servicio
 * Bluetooth. Ambos usan la misma agenda aunque la Activity no esté visible.
 */
public final class AgendaApplication extends Application {

    private AgendaStore agendaStore;

    @Override
    public void onCreate() {
        super.onCreate();
        AppPaths.ensureDirectories(this);
    }

    synchronized AgendaStore getAgendaStore() {
        if (agendaStore == null) {
            // Mismo esquema que el escritorio:
            // CenterOfNeurodivergence/data/agenda/agenda.json
            agendaStore = new AgendaStore(
                    AppPaths.appDataDir(this, "agenda").resolve("agenda.json"));
            agendaStore.load();
        }
        return agendaStore;
    }
}
