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
 * Ejecutor por aplicación Java21. Solo opera con UID Android aislado y
 * recibe descriptores Binder de solo lectura: ningún acceso al host por ruta.
 */
public final class NcappWorkerService extends Service {
    static { System.loadLibrary("neurojvm"); }

    private ParcelFileDescriptor homeDescriptor;
    private ParcelFileDescriptor appDescriptor;
    private String activeDigest;

    private static native String launchJava21(String homeFd, String appFd,
                                              String mobileClass, String nativeLibDir);
    private static native String callJava21Action(String actionId);
    private static native String callJava21HostResponse(String capability, String responseJson);

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

        @Override public synchronized String launchApp(ParcelFileDescriptor home,
                ParcelFileDescriptor archive, String mobileClass, String expectedSha256,
                String libraryDir) {
            if (!Process.isIsolated()) throw new SecurityException("No es un UID aislado.");
            if (home == null || archive == null || mobileClass == null
                    || expectedSha256 == null || libraryDir == null || activeDigest != null) {
                throw new SecurityException("Inicio inválido o repetido.");
            }
            if (!expectedSha256.matches("[a-f0-9]{64}")
                    || !mobileClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){1,15}")) {
                throw new SecurityException("Identificador o huella inválidos.");
            }
            if (!libraryDir.equals(NcappWorkerService.this.getApplicationInfo().nativeLibraryDir)) {
                throw new SecurityException("La ruta de bibliotecas no coincide con la del APK.");
            }
            try {
                homeDescriptor = home.dup();
                appDescriptor = archive.dup();
                String actual = sha256(appDescriptor.dup());
                if (!expectedSha256.equals(actual)) {
                    throw new SecurityException("El .ncapp instalado ha cambiado.");
                }
                String output = launchJava21("/proc/self/fd/" + homeDescriptor.getFd(),
                        "/proc/self/fd/" + appDescriptor.getFd(),
                        mobileClass, libraryDir);
                if (output == null || output.startsWith("NCAPP_ERROR:")) {
                    closeFiles();
                    activeDigest = null;
                } else {
                    activeDigest = expectedSha256;
                }
                return output;
            } catch (Exception failure) {
                activeDigest = null;
                closeFiles();
                throw new IllegalStateException("No se pudo iniciar HotSpot aislado.", failure);
            }
        }

        @Override public synchronized String dispatchAction(String actionId) {
            if (!Process.isIsolated() || activeDigest == null || actionId == null
                    || !actionId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new SecurityException("Acción no válida o app sin arrancar.");
            }
            return callJava21Action(actionId);
        }

        @Override public synchronized String deliverHostResponse(String capability,
                                                                  String responseJson) {
            if (!Process.isIsolated() || activeDigest == null
                    || !("agenda.read".equals(capability) || "agenda.write".equals(capability))
                    || responseJson == null || responseJson.length() > 60000) {
                throw new SecurityException("Respuesta de servicio no válida.");
            }
            return callJava21HostResponse(capability, responseJson);
        }
    };

    private synchronized void closeFiles() {
        try { if (homeDescriptor != null) homeDescriptor.close(); } catch (IOException ignored) {}
        try { if (appDescriptor != null) appDescriptor.close(); } catch (IOException ignored) {}
        homeDescriptor = null;
        appDescriptor = null;
    }

    @Override public void onDestroy() {
        closeFiles();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        if (!Process.isIsolated()) throw new SecurityException("Sandbox no aislado.");
        return binder;
    }
}
