package com.neurocenter.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import java.io.File;

import com.neurocenter.api.INcappWorker;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Ejecutor por aplicación Java21. Solo opera con UID Android aislado y
 * recibe únicamente el JAR por Binder y usa recursos instalados con el APK;
 * no abre ni recibe directorios privados del anfitrión.
 */
public final class NcappWorkerService extends Service {
    static { System.loadLibrary("neurojvm"); }

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

        @Override public String diagnosticPing() {
            String output = "OK: Binder activo; PID=" + Process.myPid()
                    + "; UID=" + Process.myUid()
                    + "; aislado=" + Process.isIsolated();
            Log.i("NeuroCenter-Sandbox", "DIAG 0: " + output);
            return Process.isIsolated() ? output : "DIAG_ERROR: el UID no está aislado";
        }

        @Override public String checkArchive(ParcelFileDescriptor archive, String expectedSha256) {
            if (!Process.isIsolated()) return "DIAG_ERROR: UID no aislado";
            if (archive == null || expectedSha256 == null
                    || !expectedSha256.matches("[a-f0-9]{64}")) {
                return "DIAG_ERROR: descriptor o SHA-256 inválidos";
            }
            Log.i("NeuroCenter-Sandbox", "DIAG 1: comprobando exclusivamente el JAR");
            try {
                String actual = sha256(archive);
                return expectedSha256.equals(actual)
                        ? "OK: SHA-256 del .ncapp coincide"
                        : "DIAG_ERROR: SHA-256 del .ncapp no coincide";
            } catch (Exception ex) {
                Log.e("NeuroCenter-Sandbox", "DIAG 1: error al leer .ncapp", ex);
                return "DIAG_ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
        }

        @Override public String checkModules() {
            if (!Process.isIsolated()) return "DIAG_ERROR: UID no aislado";
            // Android owns this extracted APK file and its SELinux label.
            // No directory FD or path under /data/data is sent over Binder.
            File image = new File(getApplicationInfo().nativeLibraryDir, "libnm.so");
            Log.i("NeuroCenter-Sandbox", "DIAG 2: comprobando JIMAGE en "
                    + image.getAbsolutePath());
            try (FileInputStream stream = new FileInputStream(image)) {
                byte[] magic = new byte[4];
                if (stream.read(magic) != 4 || (magic[0] & 0xff) != 0xda
                        || (magic[1] & 0xff) != 0xda
                        || (magic[2] & 0xff) != 0xfe
                        || (magic[3] & 0xff) != 0xca) {
                    return "DIAG_ERROR: el archivo libnm.so no contiene una JIMAGE";
                }
                if (image.length() < 1048576L) {
                    return "DIAG_ERROR: el JRE empaquetado está truncado";
                }
                return "OK: JIMAGE de Java 21 accesible desde nativeLibraryDir ("
                        + image.length() + " bytes)";
            } catch (Exception ex) {
                Log.e("NeuroCenter-Sandbox", "DIAG 2: JIMAGE inaccesible", ex);
                return "DIAG_ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
        }

        @Override public String checkNativeLibrary(String libraryDir) {
            if (!Process.isIsolated()) return "DIAG_ERROR: UID no aislado";
            if (libraryDir == null || !libraryDir.equals(getApplicationInfo().nativeLibraryDir)) {
                return "DIAG_ERROR: directorio de librerías no autorizado";
            }
            Log.i("NeuroCenter-Sandbox", "DIAG 3: comprobando exclusivamente libjvm.so");
            File lib = new File(libraryDir, "libjvm.so");
            return lib.isFile() && lib.canRead()
                    ? "OK: libjvm.so accesible" : "DIAG_ERROR: libjvm.so inaccesible";
        }

        @Override public String sha256(ParcelFileDescriptor descriptor) {
            if (!Process.isIsolated()) throw new SecurityException("Proceso no aislado.");
            if (descriptor == null) throw new IllegalArgumentException("Falta archivo.");
            // AutoCloseInputStream owns and closes the PFD exactly once.
            // Previously both FileInputStream and ParcelFileDescriptor closed
            // the same underlying FD, making diagnostics unreliable.
            try (ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
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

        @Override public synchronized String launchApp(ParcelFileDescriptor archive,
                String mobileClass, String expectedSha256,
                String libraryDir) {
            if (!Process.isIsolated()) throw new SecurityException("No es un UID aislado.");
            if (archive == null || mobileClass == null
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
                appDescriptor = archive.dup();
                String actual = sha256(appDescriptor.dup());
                if (!expectedSha256.equals(actual)) {
                    throw new SecurityException("El .ncapp instalado ha cambiado.");
                }
                Log.i("NeuroCenter-Sandbox", "LAUNCH: entrando en puente JNI de HotSpot 21");
                // java.home == Android's read-only nativeLibraryDir.
                // The vendored HotSpot binary has two audited, fixed-width
                // JIMAGE path substitutions made at build time.
                String output = launchJava21(libraryDir,
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
        try { if (appDescriptor != null) appDescriptor.close(); } catch (IOException ignored) {}
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
