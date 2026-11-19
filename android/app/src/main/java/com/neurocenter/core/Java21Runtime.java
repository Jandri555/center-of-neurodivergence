package com.neurocenter.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Instala únicamente los datos de OpenJDK 21 desde assets del APK firmado.
 * Las bibliotecas ELF de HotSpot permanecen en nativeLibraryDir (solo lectura);
 * jamás se ejecutan binarios extraídos a filesDir.
 */
public final class Java21Runtime {
    private static final String ROOT = "neurojre21";
    private static final String RELEASE_SHA256 =
            "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe";
    private static final long MAX_ASSET_SIZE = 250L * 1024 * 1024;

    static { System.loadLibrary("neurojvm"); }

    private Java21Runtime() {}

    private static native String probeNative(String jreHome, String libraryDir);

    /** NO llamar con ningún bytecode de terceros desde un proceso con permisos de host. */
    public static synchronized String probe(Context context) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("Se necesita Android 10 o superior.");
        }
        if (!"arm64-v8a".equals(Build.SUPPORTED_ABIS[0])) {
            throw new IOException("Esta versión incluye OpenJDK 21 solo para ARM64.");
        }
        // Do not extract to app-private files. HotSpot's known boot-image
        // path strings have been patched to locate libnm.so in this directory.
        File home = new File(context.getApplicationInfo().nativeLibraryDir);
        File modules = new File(home, "libnm.so");
        if (!modules.isFile() || modules.length() < 1048576L) {
            throw new IOException("Falta la JIMAGE nativa del APK: libnm.so");
        }
        return probeNative(home.getAbsolutePath(), home.getAbsolutePath());
    }

    /**
     * Exporta un descriptor que sólo permite recorrer la imagen OpenJDK 21,
     * nunca filesDir entero. El UID aislado usa /proc/self/fd/N/lib/modules.
     * La compatibilidad exacta con SELinux requiere prueba en el teléfono.
     */
    public static synchronized android.os.ParcelFileDescriptor openSandboxHome(Context context)
            throws IOException {
        File runtime = prepareDataImage(context);
        markRuntimeReadable(runtime);
        return android.os.ParcelFileDescriptor.open(runtime,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void markRuntimeReadable(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("No se puede inspeccionar el JRE.");
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) {
                markRuntimeReadable(child);
            } else if (!child.setReadable(true, false)) {
                throw new IOException("No se pudo habilitar lectura de " + child.getName());
            }
        }
        if (!directory.setReadable(true, false) || !directory.setExecutable(true, false)) {
            throw new IOException("No se pudo habilitar acceso de lectura a la imagen JRE.");
        }
    }

    private static File prepareDataImage(Context context) throws IOException {
        File base = new File(context.getFilesDir(), "jre21");
        File marker = new File(base, "source.sha256");
        File modules = new File(base, "lib/modules");
        if (marker.isFile() && modules.isFile()
                && RELEASE_SHA256.equals(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim())) {
            return base;
        }
        File staging = new File(context.getFilesDir(), "jre21-staging");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("No se pudo crear el directorio temporal JRE.");
        try {
            copyAssets(context.getAssets(), ROOT, staging, 0);
            if (!new File(staging, "lib/modules").isFile()) {
                throw new IOException("El APK no incluye lib/modules de OpenJDK 21.");
            }
            String source = new String(Files.readAllBytes(new File(staging, "source.sha256").toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!RELEASE_SHA256.equals(source)) {
                throw new IOException("La huella del runtime no coincide.");
            }
            // Solo creamos enlaces a bibliotecas ELF instaladas y no editables.
            // No se copian archivos ejecutables a almacenamiento modificable.
            File lib = new File(staging, "lib");
            File server = new File(lib, "server");
            if (!server.isDirectory() && !server.mkdirs()) {
                throw new IOException("No se pudo crear lib/server.");
            }
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            File[] libs = new File(nativeLibDir).listFiles(
                    file -> file.isFile() && file.getName().startsWith("lib")
                            && file.getName().endsWith(".so"));
            if (libs == null || libs.length == 0) {
                throw new IOException("Android no ha extraído las bibliotecas nativas del APK.");
            }
            for (File library : libs) {
                File link = new File(library.getName().equals("libjvm.so") ? server : lib,
                        library.getName());
                if (!link.exists()) {
                    Os.symlink(library.getAbsolutePath(), link.getAbsolutePath());
                }
            }
            deleteTree(base);
            if (!staging.renameTo(base)) {
                throw new IOException("No se pudo instalar la imagen de OpenJDK 21.");
            }
        } catch (Exception failure) {
            deleteTree(staging);
            if (failure instanceof IOException io) throw io;
            throw new IOException("Error preparando OpenJDK 21.", failure);
        }
        return base;
    }

    private static void copyAssets(AssetManager assets, String from, File dest, int depth)
            throws IOException {
        if (depth > 30) throw new IOException("Demasiados subdirectorios en el runtime.");
        String[] children = assets.list(from);
        if (children == null) throw new IOException("No se pudo enumerar " + from);
        if (children.length != 0) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("No se pudo crear " + dest);
            for (String child : children) {
                if (child.equals(".") || child.equals("..") || child.contains("/")
                        || child.contains("\\") || child.isEmpty()) {
                    throw new IOException("Nombre de asset no permitido.");
                }
                copyAssets(assets, from + "/" + child, new File(dest, child), depth + 1);
            }
            return;
        }
        File parent = dest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("No se pudo crear " + parent);
        File temporary = new File(parent, dest.getName() + ".pending");
        try (InputStream in = assets.open(from);
             FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32768];
            long bytes = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes += read;
                if (bytes > MAX_ASSET_SIZE) throw new IOException("Asset JRE demasiado grande.");
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        }
        if (!temporary.renameTo(dest)) throw new IOException("No se pudo guardar " + dest);
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath())) {
            Files.delete(root.toPath());
            return;
        }
        if (!root.setWritable(true, true)) {
            throw new IOException("No se pudo actualizar la imagen JRE.");
        }
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!root.delete()) throw new IOException("No se pudo eliminar " + root);
    }
}
