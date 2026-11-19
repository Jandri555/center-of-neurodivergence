package com.neurocenter.apps.agenda;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.neurocenter.core.NeuroCenterCapabilityStore;

import java.io.IOException;

/** Removes saved NeuroCenter grants when an external package is actually uninstalled. */
public final class NeuroCenterPackageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) return;
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;

        Uri data = intent.getData();
        if (data == null || !"package".equals(data.getScheme())) return;
        String packageName = data.getSchemeSpecificPart();
        if (packageName == null || packageName.equals(context.getPackageName())) return;

        try (NeuroCenterCapabilityStore store = new NeuroCenterCapabilityStore(context)) {
            store.removePackage(packageName);
        } catch (IOException | RuntimeException ignored) {
            // La identidad incluye la huella de firma: una app con otro certificado
            // nunca heredará una concesión antigua aunque falle esta limpieza.
        }
    }
}
