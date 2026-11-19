package com.neurocenter.api;

import android.os.ParcelFileDescriptor;

/** Comprobación de transporte de archivos a procesos aislados, NO ejecución JVM. */
interface INcappWorker {
    boolean isIsolated();
    int processPid();
    // Diagnostics deliberately use separate Binder calls with 0 or 1 FDs.
    String diagnosticPing();
    String checkArchive(in ParcelFileDescriptor archive, String expectedSha256);
    String checkModules();
    String checkNativeLibrary(String nativeLibDir);
    String sha256(in ParcelFileDescriptor archive);
    // DEBUG: optional one-way pipe for native stderr; RELEASE always null.
    String launchApp(in ParcelFileDescriptor archive,
                     String mobileClass, String expectedSha256, String libraryDir,
                     in ParcelFileDescriptor diagnosticSink);
    String dispatchAction(String actionId);
    String deliverHostResponse(String capability, String responseJson);
}
