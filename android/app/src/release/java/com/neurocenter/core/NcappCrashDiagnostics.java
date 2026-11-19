package com.neurocenter.core;

import android.app.Activity;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.widget.LinearLayout;

import java.util.function.Supplier;

/**
 * RELEASE variant: no diagnostic capture, no stdout/stderr pipe,
 * no crash-history inspection and no export UI.
 */
public final class NcappCrashDiagnostics implements AutoCloseable {
    private NcappCrashDiagnostics() {}

    public static boolean enabled() { return false; }
    public static NcappCrashDiagnostics open(Runnable listener) { return null; }
    public ParcelFileDescriptor sink() { return null; }
    public void closeSender() {}
    public static String report(Context context, String summary, int pid,
                                long createdAt, NcappCrashDiagnostics channel) {
        return summary;
    }
    public static void addActions(Activity activity, LinearLayout panel,
                                  Supplier<String> reportSource) {}
    @Override public void close() {}
}
