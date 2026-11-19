package com.neurocenter.api;

import android.os.ParcelFileDescriptor;

/** Comprobación de transporte de archivos a procesos aislados, NO ejecución JVM. */
interface INcappWorker {
    boolean isIsolated();
    int processPid();
    // Diagnostics deliberately use separate Binder calls with 0 or 1 FDs.
    String diagnosticPing();
    String checkArchive(in ParcelFileDescriptor archive, String expectedSha256);
    String checkModules(in ParcelFileDescriptor home);
    String checkNativeLibrary(String nativeLibDir);
    // Comprueba acceso al JRE, a libjvm.so y el hash de la .ncapp SIN iniciar JVM.
    String preflight(in ParcelFileDescriptor home, in ParcelFileDescriptor archive,
                     String expectedSha256, String libraryDir);
    // Pasos independientes para identificar qué llamada Binder falla.
    String preflightStep(int step, in ParcelFileDescriptor home,
                         in ParcelFileDescriptor archive, String expectedSha256,
                         String libraryDir);
    String sha256(in ParcelFileDescriptor archive);
    String launchApp(in ParcelFileDescriptor home, in ParcelFileDescriptor archive,
                     String mobileClass, String expectedSha256, String libraryDir);
    String dispatchAction(String actionId);
    String deliverHostResponse(String capability, String responseJson);
}
