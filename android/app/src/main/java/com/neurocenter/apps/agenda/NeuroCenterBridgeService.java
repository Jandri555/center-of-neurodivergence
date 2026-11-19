package com.neurocenter.apps.agenda;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.google.gson.Gson;
import com.neurocenter.api.INeuroCenterBridge;
import com.neurocenter.api.NeuroCenterCapabilities;
import com.neurocenter.api.NeuroCenterAppContract;
import com.neurocenter.core.AndroidCredentialVault;
import com.neurocenter.core.AndroidExternalApps;
import com.neurocenter.core.NeuroCenterCapabilityStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Cross-package bridge for services owned by NeuroCenter.
 *
 * SECURITY: every Binder call authenticates the real Binder UID and resolves its
 * sole installed package. It never trusts a package name or app ID supplied by
 * the client. Every resource operation checks its own capability on the server.
 */
public final class NeuroCenterBridgeService extends Service {

    public static final String ACTION_BIND = NeuroCenterAppContract.ACTION_BIND_BRIDGE;
    private static final String TAG = "NeuroCenterBridge";
    private final Gson gson = new Gson();

    private final INeuroCenterBridge.Stub bridge = new INeuroCenterBridge.Stub() {
        @Override
        public String getAppId() {
            return caller().appId;
        }

        @Override
        public boolean hasCapability(String capabilityId) {
            Caller caller = caller();
            if (!NeuroCenterCapabilities.isSupported(capabilityId)) return false;
            try (NeuroCenterCapabilityStore store = new NeuroCenterCapabilityStore(
                    NeuroCenterBridgeService.this)) {
                return store.isGranted(caller.packageName, caller.signingDigest, capabilityId);
            } catch (IOException | RuntimeException ex) {
                Log.e(TAG, "No se pudieron consultar las capacidades de " + caller.packageName, ex);
                return false;
            }
        }

        @Override
        public String getAgendaJson() {
            Caller caller = requireCapability(NeuroCenterCapabilities.AGENDA_READ);
            return gson.toJson(((AgendaApplication) getApplication()).getAgendaStore().all());
        }

        @Override
        public String addTask(String title, String dateIso, String timeIso, String notes) {
            Caller caller = requireCapability(NeuroCenterCapabilities.AGENDA_WRITE);
            String safeTitle = requireText(title, "El título", 512);
            validateDateAndTime(dateIso, timeIso);
            String safeNotes = notes == null ? "" : notes;
            if (safeNotes.length() > 8192) throw new IllegalArgumentException("Las notas superan 8192 caracteres.");

            AgendaEntry entry = new AgendaEntry();
            entry.type = AgendaEntry.TASK;
            entry.title = safeTitle;
            entry.date = blankToNull(dateIso);
            entry.time = blankToNull(timeIso);
            entry.notes = safeNotes;
            AgendaStore store = ((AgendaApplication) getApplication()).getAgendaStore();
            store.add(entry);
            store.save();
            return entry.id;
        }

        @Override
        public boolean setEntryCompleted(String entryId, boolean completed) {
            Caller caller = requireCapability(NeuroCenterCapabilities.AGENDA_WRITE);
            String safeId = requireEntryId(entryId);
            AgendaStore store = ((AgendaApplication) getApplication()).getAgendaStore();
            for (AgendaEntry existing : store.all()) {
                if (!safeId.equals(existing.id)) continue;
                if (existing.isEvent()) throw new IllegalArgumentException("Los eventos no se pueden marcar como tareas completadas.");
                AgendaEntry updated = existing.copy();
                updated.done = completed;
                store.replace(existing, updated);
                store.save();
                return true;
            }
            return false;
        }

        @Override
        public boolean deleteAgendaEntry(String entryId) {
            Caller caller = requireCapability(NeuroCenterCapabilities.AGENDA_WRITE);
            String safeId = requireEntryId(entryId);
            AgendaStore store = ((AgendaApplication) getApplication()).getAgendaStore();
            for (AgendaEntry existing : store.all()) {
                if (!safeId.equals(existing.id)) continue;
                store.remove(existing);
                store.save();
                return true;
            }
            return false;
        }

        @Override
        public void saveCredential(String key, char[] secret) {
            Caller caller = requireCapability(NeuroCenterCapabilities.CREDENTIALS_ACCESS);
            try {
                AndroidCredentialVault.save(NeuroCenterBridgeService.this,
                        caller.credentialOwner, key, secret);
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo guardar la credencial.", ex);
            } finally {
                if (secret != null) Arrays.fill(secret, Character.MIN_VALUE);
            }
        }

        @Override
        public char[] loadCredential(String key) {
            Caller caller = requireCapability(NeuroCenterCapabilities.CREDENTIALS_ACCESS);
            try {
                return AndroidCredentialVault.load(NeuroCenterBridgeService.this,
                        caller.credentialOwner, key);
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo recuperar la credencial.", ex);
            }
        }

        @Override
        public void deleteCredential(String key) {
            Caller caller = requireCapability(NeuroCenterCapabilities.CREDENTIALS_ACCESS);
            try {
                AndroidCredentialVault.delete(NeuroCenterBridgeService.this,
                        caller.credentialOwner, key);
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo eliminar la credencial.", ex);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_BIND.equals(intent.getAction())) return null;
        return bridge;
    }

    private Caller requireCapability(String capabilityId) {
        Caller caller = caller();
        try (NeuroCenterCapabilityStore store = new NeuroCenterCapabilityStore(this)) {
            if (!store.isGranted(caller.packageName, caller.signingDigest, capabilityId)) {
                throw new SecurityException("NeuroCenter no ha concedido «" + capabilityId
                        + "» a " + caller.packageName + ".");
            }
            return caller;
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo verificar la autorización de NeuroCenter.", ex);
        }
    }

    /**
     * Resolves identity exclusively from Binder. Requiring exactly one package
     * per UID rejects legacy sharedUserId arrangements and ambiguous identities.
     */
    private Caller caller() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) {
            throw new SecurityException("El puente solo está disponible para paquetes externos.");
        }

        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1) {
            throw new SecurityException("El UID llamante no identifica a un único paquete Android.");
        }
        String packageName = packages[0];
        if (packageName == null || packageName.equals(getPackageName())) {
            throw new SecurityException("Paquete llamante no permitido.");
        }

        try {
            ApplicationInfo info = getApplicationInfo(packageName);
            if (info.uid != uid || !info.enabled) {
                throw new SecurityException("La identidad del paquete no coincide con el UID llamante.");
            }
            if (!AndroidExternalApps.isCompatiblePackage(this, packageName)) {
                throw new SecurityException("El paquete no declara el contrato de aplicaciones de NeuroCenter.");
            }

            Bundle metadata = info.metaData;
            String appId = metadata == null ? null : metadata.getString(AndroidExternalApps.META_APP_ID);
            if (appId == null || appId.trim().isEmpty()) appId = packageName;
            if (!appId.matches("[A-Za-z0-9._-]{1,128}")) {
                throw new SecurityException("El identificador de la aplicación no es válido.");
            }
            String signer = AndroidExternalApps.signingDigest(this, packageName);
            if (signer == null || !signer.matches("[a-f0-9]{64}(,[a-f0-9]{64})*")) {
                throw new SecurityException("No se pudo verificar la firma del paquete.");
            }
            return new Caller(uid, packageName, appId, signer, credentialOwner(packageName, signer));
        } catch (PackageManager.NameNotFoundException ex) {
            throw new SecurityException("El paquete llamante ya no está instalado.", ex);
        }
    }

    private ApplicationInfo getApplicationInfo(String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= 33) {
            return getPackageManager().getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
        }
        //noinspection deprecation
        return getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
    }

    private static String credentialOwner(String packageName, String signingDigest) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((packageName + ":" + signingDigest).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xff;
                hex.append(Character.forDigit((unsigned >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(unsigned & 0xf, 16));
            }
            Arrays.fill(hash, (byte) 0);
            return hex.toString();
        } catch (Exception ex) {
            throw new SecurityException("No se pudo derivar la identidad criptográfica de la app.", ex);
        }
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + " no es válido.");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String requireEntryId(String value) {
        if (value == null || !value.matches("[A-Fa-f0-9-]{36}")) {
            throw new IllegalArgumentException("Identificador de entrada no válido.");
        }
        return value;
    }

    private static void validateDateAndTime(String dateIso, String timeIso) {
        try {
            if (dateIso != null && !dateIso.trim().isEmpty()) LocalDate.parse(dateIso.trim());
            if (timeIso != null && !timeIso.trim().isEmpty()) LocalTime.parse(timeIso.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("La fecha debe ser yyyy-MM-dd y la hora HH:mm.", ex);
        }
    }

    private static final class Caller {
        final int uid;
        final String packageName;
        final String appId;
        final String signingDigest;
        final String credentialOwner;

        Caller(int uid, String packageName, String appId, String signingDigest,
               String credentialOwner) {
            this.uid = uid;
            this.packageName = packageName;
            this.appId = appId;
            this.signingDigest = signingDigest;
            this.credentialOwner = credentialOwner;
        }
    }
}
