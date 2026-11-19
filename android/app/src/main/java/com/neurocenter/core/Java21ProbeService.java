package com.neurocenter.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.neurocenter.api.IJava21Probe;

/**
 * Diagnóstico OpenJDK en otro proceso: no interpreta aplicaciones externas.
 * Tiene UID de la app porque el sandbox de las .ncapp NO tiene acceso por ruta
 * a la imagen de módulos; esa separación aún está por resolver.
 */
public final class Java21ProbeService extends Service {
    private static final String TAG = "NeuroCenter-JRE21";
    private String lastResult;

    private final IJava21Probe.Stub binder = new IJava21Probe.Stub() {
        @Override public synchronized String probeVersion() {
            if (lastResult != null) return lastResult;
            if (Process.isIsolated()) throw new SecurityException("Sonda en proceso incorrecto.");
            try {
                lastResult = Java21Runtime.probe(Java21ProbeService.this);
            } catch (Throwable exception) {
                Log.e(TAG, "No se ha podido iniciar OpenJDK 21", exception);
                return "No se ha podido iniciar OpenJDK 21: " + exception;
            }
            return lastResult;
        }
    };

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
