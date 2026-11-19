package com.neurocenter.core;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;

/**
 * Descubre y abre aplicaciones Android externas instaladas como paquetes independientes.
 *
 * <p>Una aplicación externa debe publicar una Activity exportada con un filtro para
 * {@link #ACTION_OPEN} y una categoría DEFAULT. Android conserva cada paquete en su
 * propio UID y proceso: su código y sus permisos no se ejecutan dentro de NeuroCenter.</p>
 *
 * <p>Esta clase no carga APK, DEX ni JAR con un ClassLoader. La instalación y la concesión
 * de permisos del sistema corresponden al instalador y al Package Manager de Android.</p>
 */
public final class AndroidExternalApps {

    public static final String ACTION_OPEN = "com.neurocenter.action.OPEN_APP";
    public static final String META_APP_ID = "com.neurocenter.app.id";
    public static final String META_APP_DESCRIPTION = "com.neurocenter.app.description";

    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private AndroidExternalApps() {}

    /** Datos de una aplicación descubierta mediante el contrato público de NeuroCenter. */
    public static final class InstalledApp {
        private final String id;
        private final String packageName;
        private final String name;
        private final String description;
        private final String versionName;
        private final long versionCode;
        private final String signingDigest;
        private final ComponentName entryPoint;

        private InstalledApp(String id, String packageName, String name, String description,
                             String versionName, long versionCode, String signingDigest,
                             ComponentName entryPoint) {
            this.id = id;
            this.packageName = packageName;
            this.name = name;
            this.description = description;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signingDigest = signingDigest;
            this.entryPoint = entryPoint;
        }

        public String id() { return id; }
        public String packageName() { return packageName; }
        public String name() { return name; }
        public String description() { return description; }
        public String versionName() { return versionName; }
        public long versionCode() { return versionCode; }
        public String signingDigest() { return signingDigest; }
        public ComponentName entryPoint() { return entryPoint; }
    }

    /**
     * Devuelve únicamente los paquetes que publican explícitamente el contrato OPEN_APP.
     * No solicita QUERY_ALL_PACKAGES: la visibilidad se limita al intent-filter declarado.
     */
    public static List<InstalledApp> discover(Context context) {
        Objects.requireNonNull(context, "context");
        PackageManager manager = context.getPackageManager();
        Intent query = new Intent(ACTION_OPEN);
        query.addCategory(Intent.CATEGORY_DEFAULT);

        List<ResolveInfo> matches;
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= 33) {
            matches = manager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(flags));
        } else {
            matches = manager.queryIntentActivities(query, flags);
        }

        Map<String, InstalledApp> byId = new LinkedHashMap<>();
        for (ResolveInfo match : matches) {
            ActivityInfo activity = match.activityInfo;
            if (!isValidEntryPoint(activity, context.getPackageName())) continue;

            String packageName = activity.packageName;
            Bundle metadata = activity.applicationInfo == null ? null : activity.applicationInfo.metaData;
            String id = readMetadata(metadata, META_APP_ID, packageName);
            if (!isValidId(id) || byId.containsKey(id)) continue;

            String name = String.valueOf(match.loadLabel(manager));
            if (name.trim().isEmpty() || "null".equals(name)) name = packageName;
            String description = readMetadata(metadata, META_APP_DESCRIPTION, "");

            String versionName = "";
            long versionCode = 0L;
            try {
                PackageInfo info = getPackageInfo(manager, packageName);
                versionName = info.versionName == null ? "" : info.versionName;
                if (Build.VERSION.SDK_INT >= 28) {
                    versionCode = info.getLongVersionCode();
                } else {
                    versionCode = info.versionCode;
                }
                String signer = signingDigest(info);
                if (signer == null) continue;
            } catch (PackageManager.NameNotFoundException ignored) {
                // El paquete puede desinstalarse mientras se actualiza el listado.
                continue;
            }

            ComponentName component = new ComponentName(packageName, activity.name);
            byId.put(id, new InstalledApp(id, packageName, name, description,
                    versionName, versionCode, signer, component));
        }

        List<InstalledApp> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(InstalledApp::name, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }

    /** Abre una aplicación externa mediante su Activity publicada y explícita. */
    public static void launch(Context context, InstalledApp app) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(app, "app");
        if (context.getPackageName().equals(app.packageName())) {
            throw new SecurityException("NeuroCenter no puede abrirse a sí mismo como app externa.");
        }

        Intent intent = new Intent(ACTION_OPEN);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setComponent(app.entryPoint());
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            throw new IllegalStateException(
                    "La aplicación «" + app.name() + "» ya no está instalada o ha retirado su punto de entrada.", ex);
        }
    }

    /** Comprueba si el usuario ha autorizado a NeuroCenter a iniciar instalaciones externas. */
    public static boolean canRequestPackageInstalls(Context context) {
        Objects.requireNonNull(context, "context");
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Entrega un APK al instalador oficial de Android.
     *
     * <p>El usuario revisa y confirma la instalación. Si aún no ha autorizado a NeuroCenter
     * como fuente de instalación, se abre la pantalla del sistema para que decida.</p>
     */
    public static void requestInstall(Context context, Uri apkUri) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(apkUri, "apkUri");
        if (!"content".equalsIgnoreCase(apkUri.getScheme())) {
            throw new IllegalArgumentException("El APK debe proporcionarse mediante un URI content://.");
        }

        if (!canRequestPackageInstalls(context)) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof android.app.Activity)) {
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(settings);
            return;
        }

        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(apkUri, APK_MIME_TYPE);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof android.app.Activity)) {
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(install);
    }

    /** Abre la confirmación de desinstalación del sistema para un paquete externo. */
    public static void requestUninstall(Context context, String packageName) {
        Objects.requireNonNull(context, "context");
        if (!isValidPackageName(packageName) || context.getPackageName().equals(packageName)) {
            throw new IllegalArgumentException("Nombre de paquete no válido.");
        }
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
        if (!(context instanceof android.app.Activity)) {
            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(uninstall);
    }

    private static boolean isValidEntryPoint(ActivityInfo activity, String hostPackage) {
        return activity != null
                && activity.applicationInfo != null
                && activity.enabled
                && activity.exported
                && activity.packageName != null
                && !hostPackage.equals(activity.packageName)
                && activity.name != null
                && !activity.name.trim().isEmpty();
    }

    private static String readMetadata(Bundle metadata, String key, String fallback) {
        if (metadata == null || !metadata.containsKey(key)) return fallback;
        String value = metadata.getString(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static boolean isValidPackageName(String name) {
        return name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private static String signingDigest(PackageInfo info) {
        Signature[] signers = null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signers = info.signingInfo.getApkContentsSigners();
        } else if (info.signatures != null) {
            signers = info.signatures;
        }
        if (signers == null || signers.length == 0) return null;

        try {
            List<String> hashes = new ArrayList<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signer : signers) {
                byte[] bytes = digest.digest(signer.toByteArray());
                StringBuilder hex = new StringBuilder(bytes.length * 2);
                for (byte value : bytes) {
                    int unsigned = value & 0xff;
                    hex.append(Character.forDigit((unsigned >>> 4) & 0xf, 16));
                    hex.append(Character.forDigit(unsigned & 0xf, 16));
                }
                hashes.add(hex.toString());
            }
            Collections.sort(hashes);
            return String.join(",", hashes);
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo getPackageInfo(PackageManager manager, String packageName)
            throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 33) {
            return manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
        }
        return manager.getPackageInfo(packageName, flags);
    }
}
