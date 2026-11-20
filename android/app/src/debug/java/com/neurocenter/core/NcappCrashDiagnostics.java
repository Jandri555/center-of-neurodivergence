package com.neurocenter.core;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Only in the DEBUG source set. No READ_LOGS permission, root, external files,
 * native sandbox bypass, or calls to logcat. Receives stdout/stderr and
 * HotSpot diagnostics via a one-way pipe sent through Binder.
 *
 * Data is held in memory, bounded to the most recent 64 KiB, never uploaded.
 * The user must explicitly copy/share a report. It may contain app data.
 */
public final class NcappCrashDiagnostics implements AutoCloseable {
    private static final int MAX_CAPTURE = 64 * 1024;
    private final ParcelFileDescriptor reader;
    private final ParcelFileDescriptor writer;
    private final StringBuilder tail = new StringBuilder();
    private final Runnable onUpdate;
    private volatile boolean closed;

    private NcappCrashDiagnostics(ParcelFileDescriptor reader,
                                  ParcelFileDescriptor writer, Runnable onUpdate) {
        this.reader = reader;
        this.writer = writer;
        this.onUpdate = onUpdate;
        Thread consumer = new Thread(this::drain, "ncapp-debug-pipe");
        consumer.setDaemon(true);
        consumer.start();
    }

    public static boolean enabled() { return true; }

    public static NcappCrashDiagnostics open(Runnable onUpdate) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        if (pipe == null || pipe.length != 2) {
            throw new IOException("Android no pudo crear la tubería de diagnóstico.");
        }
        return new NcappCrashDiagnostics(pipe[0], pipe[1], onUpdate);
    }

    public ParcelFileDescriptor sink() { return writer; }

    public void closeSender() {
        try { writer.close(); } catch (IOException ignored) {}
    }

    private void drain() {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(reader)) {
            byte[] bytes = new byte[4096];
            int n;
            while (!closed && (n = in.read(bytes)) >= 0) {
                if (n == 0) continue;
                String chunk = new String(bytes, 0, n, StandardCharsets.UTF_8);
                synchronized (tail) {
                    tail.append(chunk);
                    if (tail.length() > MAX_CAPTURE) {
                        tail.delete(0, tail.length() - MAX_CAPTURE);
                    }
                }
                if (onUpdate != null) onUpdate.run();
            }
        } catch (IOException ignored) {
            // Closing the session or a dead worker closes the pipe.
        } finally {
            if (onUpdate != null && !closed) onUpdate.run();
        }
    }

    private String captured() {
        synchronized (tail) { return tail.toString(); }
    }

    public static String report(Context context, String summary, int pid,
                                long createdAt, NcappCrashDiagnostics channel) {
        StringBuilder report = new StringBuilder(summary);
        report.append("\n\n--- Estado de Android ---\n");
        if (Build.VERSION.SDK_INT < 30) {
            report.append("Android 10: historial de cierres no disponible");
        } else if (pid <= 0) {
            report.append("No se llegó a obtener el PID aislado");
        } else {
            try {
                ActivityManager am = context.getSystemService(ActivityManager.class);
                List<ApplicationExitInfo> info = am == null ? null
                        : am.getHistoricalProcessExitReasons(
                                context.getPackageName(), pid, 5);
                ApplicationExitInfo match = null;
                if (info != null) for (ApplicationExitInfo entry : info) {
                    if (entry.getPid() == pid
                            && entry.getTimestamp() >= createdAt - 5000) {
                        match = entry;
                        break;
                    }
                }
                if (match == null) {
                    report.append("Android no ha registrado todavía la salida del PID ")
                            .append(pid);
                } else {
                    report.append("Motivo (código): ").append(match.getReason())
                            .append("\nEstado/señal: ").append(match.getStatus())
                            .append("\nDetalle: ").append(match.getDescription());
                    if (Build.VERSION.SDK_INT >= 31
                            && match.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        report.append("\\n\\n--- Tombstone nativa del proceso aislado ---\\n")
                                .append(NcappTombstoneSummary.read(match));
                    }
                }
            } catch (RuntimeException ex) {
                report.append("No se pudo consultar el historial: ")
                        .append(ex.getClass().getSimpleName());
            }
        }
        report.append("\n\n--- Captura directa de HotSpot / stdout / stderr ---\n");
        if (channel == null) {
            report.append("No se pudo crear el canal de captura. Puede necesitarse ADB.");
        } else {
            String output = channel.captured();
            report.append(output.isEmpty()
                    ? "(Todavía no hay salida nativa. Puede que el proceso haya salido "
                            + "antes de abrir el canal o haya escrito solo a logcat.)"
                    : output);
        }
        report.append("\n\nLos mensajes pueden incluir datos privados de la .ncapp; ")
                .append("revísalos antes de compartir.");
        return report.toString();
    }

    public static void addActions(Activity activity, LinearLayout panel,
                                  Supplier<String> reportSource) {
        Button copy = new Button(activity);
        copy.setText("Copiar informe completo");
        copy.setAllCaps(false);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) activity
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "NeuroCenter diagnóstico debug", reportSource.get()));
            }
        });
        panel.addView(copy);

        Button share = new Button(activity);
        share.setText("Compartir informe (sin ADB)");
        share.setAllCaps(false);
        share.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Informe NeuroCenter Debug");
            intent.putExtra(Intent.EXTRA_TEXT, reportSource.get());
            activity.startActivity(Intent.createChooser(intent, "Compartir diagnóstico"));
        });
        panel.addView(share);
    }

    @Override public void close() {
        closed = true;
        closeSender();
        try { reader.close(); } catch (IOException ignored) {}
    }
}
