package com.neurocenter.core;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Uses OpenJDK 21 libraries and a JIMAGE installed by Android directly from
 * the signed APK. Never extracts the runtime under /data/data or filesDir.
 *
 * The native libjvm has two build-verified, fixed-width boot-image path
 * replacements (see android/scripts/prepare_java21_runtime.py).
 */
public final class Java21Runtime {
    static { System.loadLibrary("neurojvm"); }

    private Java21Runtime() {}

    private static native String probeNative(String jreHome, String nativeLibDir);

    /**
     * Trusted diagnostic only: no third-party .ncapp classes are loaded here.
     * Real app execution occurs in the android:isolatedProcess worker.
     */
    public static String probe(Context context) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("NeuroCenter requiere Android 10 o posterior.");
        }
        if (!"arm64-v8a".equals(Build.SUPPORTED_ABIS[0])) {
            throw new IOException("La JVM empaquetada actualmente requiere ARM64.");
        }
        File home = new File(context.getApplicationInfo().nativeLibraryDir);
        File image = new File(home, "libnm.so");
        if (!image.isFile() || image.length() < 1024 * 1024L) {
            throw new IOException("No está instalada la imagen de módulos Java 21 (libnm.so).");
        }
        try (FileInputStream source = new FileInputStream(image)) {
            byte[] signature = new byte[4];
            if (source.read(signature) != 4
                    || (signature[0] & 0xff) != 0xda
                    || (signature[1] & 0xff) != 0xda
                    || (signature[2] & 0xff) != 0xfe
                    || (signature[3] & 0xff) != 0xca) {
                throw new IOException("La imagen de módulos Java 21 está dañada.");
            }
        }
        return probeNative(home.getAbsolutePath(), home.getAbsolutePath());
    }
}
