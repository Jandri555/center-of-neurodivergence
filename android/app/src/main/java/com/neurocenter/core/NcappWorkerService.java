package com.neurocenter.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import com.neurocenter.api.INcappWorker;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Primer paso del sandbox Android: servicio sin permisos de la app principal.
 * Solo comprueba un descriptor recibido por Binder. NO carga ni ejecuta JAR.
 */
public final class NcappWorkerService extends Service {
    private final INcappWorker.Stub binder = new INcappWorker.Stub() {
        @Override public boolean isIsolated() {
            return Process.isIsolated();
        }

        @Override public String sha256(ParcelFileDescriptor descriptor) {
            if (!Process.isIsolated()) throw new SecurityException("Proceso no aislado.");
            if (descriptor == null) throw new IllegalArgumentException("Falta archivo.");
            try (ParcelFileDescriptor file = descriptor;
                 FileInputStream input = new FileInputStream(file.getFileDescriptor())) {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[32 * 1024];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 128L * 1024 * 1024) {
                        throw new IllegalArgumentException("Archivo demasiado grande.");
                    }
                    hash.update(buffer, 0, read);
                }
                StringBuilder result = new StringBuilder();
                for (byte b : hash.digest()) {
                    result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                }
                return result.toString();
            } catch (Exception ex) {
                throw new IllegalStateException("Fallo al comprobar archivo aislado.", ex);
            }
        }
    };

    @Override public IBinder onBind(Intent intent) {
        if (!Process.isIsolated()) throw new SecurityException("Sandbox no aislado.");
        return binder;
    }
}
