package com.neurocenter.apps.agenda;

import android.app.Application;
import android.os.Build;
import android.os.Process;
import android.util.Log;

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
        // Android crea Application también para el proceso de NcappWorkerService.
        // Su UID aislado no puede acceder a filesDir del anfitrión.
        // Intentar preparar las carpetas aquí hace fallar el proceso ANTES de onBind().
        if (Process.isIsolated()) {
            Log.i("NeuroCenter-Sandbox", "Application iniciada en UID aislado "
                    + Process.myUid() + "; se omite AppPaths.ensureDirectories.");
            return;
        }
        AppPaths.ensureDirectories(this);
    }

    synchronized AgendaStore getAgendaStore() {
        if (agendaStore == null) {
            // Mismo esquema que el escritorio:
            // CenterOfNeurodivergence/data/agenda/agenda.json
            String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            agendaStore = new AgendaStore(
                    AppPaths.appDataDir(this, "agenda").resolve("agenda.json"),
                    deviceName);
            agendaStore.load();
        }
        return agendaStore;
    }
}
