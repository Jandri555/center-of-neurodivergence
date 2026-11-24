package com.neurocenter.core;

import android.content.Context;
import android.net.Uri;
import com.neurocenter.api.NeuroCenterCapabilities;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Instala aplicaciones .ncapp como archivos JAR normales de Java 21.
 * No instala APK, no carga bytecode DEX y NO ejecuta código externo.
 */
public final class NcappManager {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private static final String EXTENSION = ".ncapp";

    public record InstalledApp(String id, String name, String version, String author,
                               String sha256, String mobileClass, File archive,
                               Set<String> declaredPermissions) {}

    private NcappManager() {}

    private static File directory(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "ncapps");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No se pudo crear el directorio privado de aplicaciones.");
        }
        return dir;
    }

    /**
     * Temporary validated package and the existing installation with the same ID.
     * Package identity never depends on the picked document's filename.
     */
    public record PreparedApp(File staged, InstalledApp candidate, InstalledApp previous) {}

    public static PreparedApp prepare(Context context, Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()))
            throw new IOException("Selecciona un archivo local con el gestor de documentos.");
        File dir = directory(context);
        File staged = File.createTempFile("ncapp-import-", ".tmp", dir);
        boolean ok = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(staged)) {
                if (input == null) throw new IOException("No se pudo abrir el archivo seleccionado.");
                byte[] buffer = new byte[32 * 1024];
                long bytes = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > MAX_BYTES) throw new IOException("El paquete supera 128 MiB.");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            InstalledApp candidate = inspect(staged);
            File installed = new File(dir, candidate.id() + EXTENSION);
            InstalledApp previous = installed.isFile() ? inspect(installed) : null;
            ok = true;
            return new PreparedApp(staged, candidate, previous);
        } finally {
            if (!ok) Files.deleteIfExists(staged.toPath());
        }
    }

    /**
     * Install or update in one atomic rename. An open read-only descriptor
     * continues referencing the old inode; readers opening the app afterwards
     * get the complete new file, never a half-copied archive.
     */
    public static InstalledApp activate(Context context, PreparedApp prepared) throws IOException {
        File dir = directory(context);
        File destination = new File(dir, prepared.candidate().id() + EXTENSION);
        // Reject changes on disk while the user was confirming.
        InstalledApp candidate = inspect(prepared.staged());
        if (!candidate.sha256().equals(prepared.candidate().sha256())
                || !candidate.id().equals(prepared.candidate().id()))
            throw new IOException("El archivo seleccionado cambió durante la instalación.");
        if (prepared.previous() != null) {
            if (!destination.isFile()
                    || !inspect(destination).sha256().equals(prepared.previous().sha256()))
                throw new IOException("La aplicación instalada cambió durante la actualización.");
        } else if (destination.exists()) {
            throw new IOException("Se ha instalado otra versión durante la confirmación.");
        }
        if (!prepared.staged().setReadable(true, false))
            throw new IOException("No se pudo preparar el .ncapp para el proceso aislado.");
        try {
            Files.move(prepared.staged().toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Este almacenamiento no admite actualización atómica segura.", unsupported);
        }
        return inspect(destination);
    }

    public static void discard(PreparedApp prepared) {
        if (prepared == null) return;
        try { Files.deleteIfExists(prepared.staged().toPath()); }
        catch (IOException ignored) { /* The temporary path is not installed. */ }
    }

    /** Backwards-compatible installation API for internal callers. */
    public static InstalledApp install(Context context, Uri uri) throws IOException {
        PreparedApp prepared = prepare(context, uri);
        try {
            return activate(context, prepared);
        } finally {
            discard(prepared);
        }
    }

    public static List<InstalledApp> list(Context context) throws IOException {
        File[] files = directory(context).listFiles(file ->
                file.isFile() && file.getName().endsWith(EXTENSION));
        if (files == null) throw new IOException("No se pudo leer la carpeta de aplicaciones.");
        List<InstalledApp> apps = new ArrayList<>();
        for (File file : files) {
            try {
                InstalledApp app = inspect(file);
                if (file.getName().equals(app.id() + EXTENSION)) apps.add(app);
            } catch (IOException ignored) {
                // No se muestran paquetes dañados ni archivos con nombre falso.
            }
        }
        apps.sort(Comparator.comparing(InstalledApp::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(apps);
    }

    public static void uninstall(Context context, InstalledApp app) throws IOException {
        File file = new File(directory(context), app.id() + EXTENSION);
        if (!file.getCanonicalFile().equals(app.archive().getCanonicalFile())) {
            throw new SecurityException("El archivo no pertenece al directorio de apps.");
        }
        if (!Files.deleteIfExists(file.toPath())) {
            throw new IOException("La aplicación no está instalada.");
        }
    }

    /** Solo examina el archivo: la ejecución llegará después de integrar el JRE 21. */
    public static InstalledApp inspect(File file) throws IOException {
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) {
            throw new IOException("El paquete está vacío o supera 128 MiB.");
        }
        try (JarFile jar = new JarFile(file, true)) {
            JarEntry manifestEntry = jar.getJarEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null || manifestEntry.getSize() < 0
                    || manifestEntry.getSize() > 65536) {
                throw new IOException("El manifiesto falta o supera 64 KiB.");
            }
            Manifest manifest;
            try (InputStream input = jar.getInputStream(manifestEntry);
                 ByteArrayOutputStream bounded = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bounded.size() + n > 65536) {
                        throw new IOException("El manifiesto supera 64 KiB.");
                    }
                    bounded.write(buffer, 0, n);
                }
                manifest = new Manifest(new ByteArrayInputStream(bounded.toByteArray()));
            }
            Attributes meta = manifest.getMainAttributes();
            String id = required(meta, "NeuroCenter-App-Id");
            if (!id.matches("[A-Za-z][A-Za-z0-9._-]{0,63}") || id.contains("..")) {
                throw new IOException("NeuroCenter-App-Id no válido.");
            }
            if (!"true".equalsIgnoreCase(required(meta, "NeuroCenter-App-Mobile"))) {
                throw new IOException("Esta aplicación no declara compatibilidad con móvil.");
            }
            String mobileClass = required(meta, "NeuroCenter-Mobile-Class");
            if (!mobileClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){1,15}")) {
                throw new IOException("NeuroCenter-Mobile-Class debe ser una clase Java válida.");
            }
            Set<String> declaredPermissions = new java.util.LinkedHashSet<>();
            String permissionList = meta.getValue("NeuroCenter-App-Permissions");
            if (permissionList != null && !permissionList.isBlank()) {
                if (permissionList.length() > 2048) throw new IOException("Lista de permisos demasiado larga.");
                for (String raw : permissionList.split(",", -1)) {
                    String permission = raw.trim();
                    if (!NeuroCenterCapabilities.isSupported(permission)) {
                        throw new IOException("Permiso no reconocido en el manifiesto: " + permission);
                    }
                    if (!declaredPermissions.add(permission)) {
                        throw new IOException("Permiso duplicado en el manifiesto: " + permission);
                    }
                }
            }
            String desktop = meta.getValue("NeuroCenter-App-Desktop");
            if (desktop != null && !desktop.equalsIgnoreCase("true")
                    && !desktop.equalsIgnoreCase("false")) {
                throw new IOException("NeuroCenter-App-Desktop debe ser true o false.");
            }
            boolean containsClass = false;
            int entries = 0;
            Set<String> names = new HashSet<>();
            Enumeration<JarEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                entries++;
                if (entries > MAX_ENTRIES) throw new IOException("El paquete contiene demasiadas entradas.");
                String entryName = entry.getName();
                if (entryName.startsWith("/") || entryName.contains("..")
                        || entryName.contains("\\") || !names.add(entryName)) {
                    throw new IOException("El paquete incluye rutas peligrosas o duplicadas.");
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_BYTES) {
                    throw new IOException("Hay una entrada con tamaño inválido.");
                }
                if (entryName.endsWith(".class")) containsClass = true;
            }
            if (!containsClass) throw new IOException("No hay clases Java en la aplicación.");
            if (!names.contains(mobileClass.replace('.', '/') + ".class")) {
                throw new IOException("La clase móvil declarada no existe dentro del .ncapp.");
            }
            return new InstalledApp(id, optional(meta, "Implementation-Title", id),
                    optional(meta, "Implementation-Version", "Sin versión"),
                    optional(meta, "Implementation-Vendor", "Desconocido"),
                    digest(file), mobileClass, file, Set.copyOf(declaredPermissions));
        } catch (IllegalArgumentException error) {
            throw new IOException("Archivo .ncapp inválido.", error);
        }
    }

    private static String required(Attributes attrs, String name) throws IOException {
        String value = attrs.getValue(name);
        if (value == null || value.isBlank()) throw new IOException("Falta " + name + ".");
        return value.trim();
    }

    private static String optional(Attributes attrs, String name, String fallback) {
        String value = attrs.getValue(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String digest(File file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) sha.update(buffer, 0, n);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : sha.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("Android no proporciona SHA-256.", ex);
        }
    }
}
