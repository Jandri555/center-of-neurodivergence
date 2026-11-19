package com.neurocenter.api;

import android.os.ParcelFileDescriptor;

/** Comprobación de transporte de archivos a procesos aislados, NO ejecución JVM. */
interface INcappWorker {
    boolean isIsolated();
    String sha256(in ParcelFileDescriptor archive);
}
