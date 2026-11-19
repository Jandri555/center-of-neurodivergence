package com.neurocenter.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

import java.io.File;
import java.io.FileDescriptor;

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

        @Override public int processPid() {
            return Process.myPid();
        }

        /** Preflight: verifica FD y librerías sin crear una segunda JVM. */
        @Override public String preflight(ParcelFileDescriptor home,
                ParcelFileDescriptor archive, String expectedSha256, String libraryDir) {
            if (!Process.isIsolated()) return "DIAG_ERROR: El servicio no tiene UID aislado.";
            if (home == null || archive == null || expectedSha256 == null
                    || libraryDir == null || !expectedSha256.matches("[a-f0-9]{64}")) {
                return "DIAG_ERROR: Parámetros del sandbox inválidos.";
            }
            if (!libraryDir.equals(getApplicationInfo().nativeLibraryDir)) {
                return "DIAG_ERROR: La ruta de bibliotecas no pertenece al APK.";
            }
            try {
                Log.i("NeuroCenter-Sandbox", "PRECHECK 1/4: worker PID=" + Process.myPid());
                String actual = sha256(archive.dup());
                if (!expectedSha256.equals(actual)) {
                    return "DIAG_ERROR: La huella SHA-256 de la .ncapp no coincide.";
                }
                Log.i("NeuroCenter-Sandbox", "PRECHECK 2/4: jar recibido por Binder y SHA-256 correcto");
                String modulePath = "/proc/self/fd/" + home.getFd() + "/lib/modules";
                FileDescriptor modules = null;
                try {
                    modules = Os.open(modulePath, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                    if (Os.fstat(modules).st_size < 1024) {
                        return "DIAG_ERROR: Los módulos OpenJDK 21 están vacíos.";
                    }
                } finally {
                    if (modules != null) Os.close(modules);
                }
                Log.i("NeuroCenter-Sandbox", "PRECHECK 3/4: lib/modules accesible desde el UID aislado");
                File jvm = new File(libraryDir, "libjvm.so");
                if (!jvm.isFile() || !jvm.canRead()) {
                    return "DIAG_ERROR: El proceso aislado no puede leer libjvm.so.";
                }
                Log.i("NeuroCenter-Sandbox", "PRECHECK 4/4: libjvm.so accesible");
                return "OK: acceso a paquete, módulos y libjvm.so verificado.";
            } catch (Exception ex) {
                Log.e("NeuroCenter-Sandbox", "Fallo de preflight de la JVM aislada", ex);
                return "DIAG_ERROR: " + ex.getClass().getSimpleName()
                        + ": " + (ex.getMessage() == null ? "sin detalle" : ex.getMessage());
            }
        }

        /**
         * Diagnostic steps, one Binder transaction each:
         * 1 = SHA256 on duplicated archive FD; 2 = JRE modules; 3 = libjvm.
         * No Java21 code is started and no extra host permissions are granted.
         */
        @Override public String preflightStep(int step, ParcelFileDescriptor home,
                ParcelFileDescriptor archive, String expectedSha256, String libraryDir) {
            if (!Process.isIsolated()) return "DIAG_ERROR: UID no aislado";
            if (home == null || archive == null || expectedSha256 == null
                    || libraryDir == null || !expectedSha256.matches("[a-f0-9]{64}")
                    || !libraryDir.equals(getApplicationInfo().nativeLibraryDir)) {
                return "DIAG_ERROR: Parámetros inválidos";
            }
            Log.i("NeuroCenter-Sandbox", "PREFLIGHT etapa " + step + " antes de ejecutar");
            try {
                if (step == 1) {
                    String digest = sha256(archive.dup());
                    return expectedSha256.equals(digest)
                            ? "OK: SHA-256 del archivo .ncapp"
                            : "DIAG_ERROR: SHA-256 no coincide";
                }
                if (step == 2) {
                    String path = "/proc/self/fd/" + home.getFd() + "/lib/modules";
                    FileDescriptor fd = null;
                    try {
                        fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                        return Os.fstat(fd).st_size > 1024
                                ? "OK: imagen de módulos OpenJDK 21"
                                : "DIAG_ERROR: lib/modules está vacío";
                    } finally {
                        if (fd != null) Os.close(fd);
                    }
                }
                if (step == 3) {
                    File jvm = new File(libraryDir, "libjvm.so");
                    return jvm.isFile() && jvm.canRead()
                            ? "OK: libjvm.so legible"
                            : "DIAG_ERROR: no se puede leer libjvm.so";
                }
                return "DIAG_ERROR: etapa desconocida " + step;
            } catch (Exception e) {
                Log.e("NeuroCenter-Sandbox", "PREFLIGHT " + step + " falló", e);
                return "DIAG_ERROR: etapa " + step + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
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
                Log.i("NeuroCenter-Sandbox", "LAUNCH: preparando FD de Java 21 y la .ncapp");
                homeDescriptor = home.dup();
                appDescriptor = archive.dup();
                String actual = sha256(appDescriptor.dup());
                if (!expectedSha256.equals(actual)) {
                    throw new SecurityException("El .ncapp instalado ha cambiado.");
                }
                Log.i("NeuroCenter-Sandbox", "LAUNCH: entrando en puente JNI de HotSpot 21");
                String output = launchJava21("/proc/self/fd/" + homeDescriptor.getFd(),
                        "/proc/self/fd/" + appDescriptor.getFd(),
                        mobileClass, libraryDir);
                Log.i("NeuroCenter-Sandbox", "LAUNCH: puente JNI devolvió resultado");
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

    @Override public void onCreate() {
        super.onCreate();
        Log.i("NeuroCenter-Sandbox", "Servicio ncapp creado, UID="
                + Process.myUid() + ", isIsolated=" + Process.isIsolated());
    }

    @Override public IBinder onBind(Intent intent) {
        if (!Process.isIsolated()) throw new SecurityException("Sandbox no aislado.");
        Log.i("NeuroCenter-Sandbox", "onBind correcto en UID aislado " + Process.myUid());
        return binder;
    }
}
