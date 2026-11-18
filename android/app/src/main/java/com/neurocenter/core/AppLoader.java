package com.neurocenter.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.neurocenter.api.NeuroMobileApp;
import com.neurocenter.api.NeuroPlatform;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * Instala, desinstala y carga apps externas en Android.
 *
 * <p>Un paquete es un {@code .apk} o un {@code .jar} que contenga {@code classes.dex}
 * (un JAR de escritorio con clases Swing no se puede ejecutar en Android).
 * Cada paquete se carga con su propio DexClassLoader, cuyo padre es el ClassLoader del host,
 * así que ve la API ({@code com.neurocenter.api.*}) sin empaquetarla.</p>
 *
 * <p>Compatibilidad: flags {@code NeuroCenter-App-Desktop} / {@code NeuroCenter-App-Mobile}
 * como {@code <meta-data>} del {@code <application>} (APK) o como entradas del manifiesto (JAR).
 * Clases de entrada: metadato {@code NeuroCenter-App-Class} (lista separada por comas) y/o
 * {@code META-INF/services/com.neurocenter.api.NeuroMobileApp}.</p>
 *
 * <p>Android 14+ exige que el código cargado dinámicamente sea de solo lectura;
 * los paquetes se marcan así al instalarlos y de nuevo antes de cada carga.</p>
 */
public final class AppLoader {

    private static final String TAG = "AppLoader";
    private static final long MAX_PACKAGE_BYTES = 128L * 1024 * 1024;

    public static final String META_ID = "NeuroCenter-App-Id";
    public static final String META_CLASS = "NeuroCenter-App-Class";
    public static final String META_AUTHOR = "NeuroCenter-App-Author";
    private static final String SERVICES_ENTRY = "META-INF/services/" + NeuroMobileApp.class.getName();

    public static final class LoadedApp {
        public final NeuroMobileApp app;
        public final String fileName;

        LoadedApp(NeuroMobileApp app, String fileName) {
            this.app = app;
            this.fileName = fileName;
        }
    }

    public static final class Result {
        public final List<LoadedApp> apps;
        public final List<String> errors;

        Result(List<LoadedApp> apps, List<String> errors) {
            this.apps = apps;
            this.errors = errors;
        }
    }

    /** Metadatos leídos de un paquete. */
    private static final class Meta {
        String id, name, author, version;
        List<String> classNames = new ArrayList<>();
        PlatformCompatibility.Targets targets;
    }

    private AppLoader() {}

    // ------------------------------------------------------------------ instalar / desinstalar

    /**
     * Copia el paquete a apps/, valida su manifiesto y lo registra. Se puede llamar desde un hilo
     * de fondo. No carga el código: para eso usa {@link #load}.
     */
    public static AppRegistry.AppRecord install(Context context, AppRegistry registry,
                                                InputStream source, String displayName) throws IOException {
        if (source == null || displayName == null) throw new IOException("No se pudo leer el archivo.");
        String fileName = safeFileName(displayName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk") && !lower.endsWith(".jar"))
            throw new IOException("Selecciona un archivo .apk o .jar.");

        Path dir = AppPaths.appsDir(context);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        if (Files.exists(target)) throw new IOException("Ya existe un archivo con ese nombre.");
        Path part = dir.resolve(fileName + ".part");

        boolean registered = false;
        boolean moved = false;
        try {
            copyLimited(source, part);
            Meta meta = readMeta(context, part.toFile(), isApk(fileName), fileName);
            meta.targets.require(NeuroPlatform.MOBILE, "la app «" + meta.name + "»");
            if (meta.classNames.size() != 1) {
                throw new IOException("Cada paquete Android debe declarar exactamente una clase "
                        + "NeuroMobileApp. Esto permite asociar identidad, datos y permisos a una sola app.");
            }
            if (meta.id.trim().isEmpty()) throw new IOException("El paquete no proporciona un identificador válido.");
            if (registry.containsId(meta.id))
                throw new IOException("Ya hay una app instalada con el identificador «" + meta.id + "».");

            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            if (!target.toFile().setReadOnly()) {
                throw new IOException("No se pudo marcar el paquete como solo lectura "
                        + "(Android 14+ lo exige para cargar código).");
            }

            AppRegistry.AppRecord record = registry.register(meta.id, meta.name, meta.author,
                    meta.version, fileName, Files.size(target));
            registered = true;
            return record;
        } finally {
            if (!registered) {
                try {
                    Files.deleteIfExists(part);
                    if (moved) {
                        target.toFile().setWritable(true);
                        Files.deleteIfExists(target);
                    }
                } catch (IOException ex) {
                    Log.w(TAG, "No se pudo limpiar la instalación fallida", ex);
                }
            }
        }
    }

    /** Borra el paquete, su caché compilada y su fila del registro. */
    public static void uninstall(Context context, AppRegistry registry, AppRegistry.AppRecord record)
            throws IOException {
        Path dir = AppPaths.appsDir(context).toAbsolutePath().normalize();
        Path file = dir.resolve(record.fileName).normalize();
        if (!file.startsWith(dir) || file.equals(dir)) throw new IOException("Ruta de paquete no válida.");

        registry.removeByFileName(record.fileName);
        file.toFile().setWritable(true);
        Files.deleteIfExists(file);
        deleteCompiledCache(dir, record.fileName);
    }

    /** Elimina oat/<isa>/<nombre>.odex|vdex|art que ART genera junto al paquete. */
    private static void deleteCompiledCache(Path appsDir, String fileName) {
        Path oat = appsDir.resolve("oat");
        if (!Files.isDirectory(oat)) return;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        try (Stream<Path> walk = Files.walk(oat)) {
            List<Path> matches = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(base + "."))
                    .forEach(matches::add);
            for (Path match : matches) Files.deleteIfExists(match);
        } catch (IOException | RuntimeException ex) {
            Log.w(TAG, "No se pudo limpiar la caché compilada de " + fileName, ex);
        }
    }

    // ------------------------------------------------------------------ cargar

    /** Carga solo los paquetes presentes en el registro de instalaciones. */
    public static Result load(Context context, List<String> installedFileNames) {
        List<LoadedApp> apps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Path dir = AppPaths.appsDir(context);
        if (!Files.isDirectory(dir)) return new Result(apps, errors);

        List<String> names = new ArrayList<>(installedFileNames);
        java.util.Collections.sort(names);
        for (String name : names) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                errors.add(name + ": el archivo ya no existe en la carpeta de apps.");
                continue;
            }
            try {
                loadPackage(context, file, name, apps);
            } catch (Throwable error) {
                errors.add(name + ": " + error);
            }
        }
        return new Result(apps, errors);
    }

    private static void loadPackage(Context context, Path file, String name, List<LoadedApp> out)
            throws Exception {
        File packageFile = file.toFile();
        Meta meta = readMeta(context, packageFile, isApk(name), name);
        meta.targets.require(NeuroPlatform.MOBILE, "la app «" + meta.name + "»");
        if (meta.classNames.size() != 1)
            throw new IOException("El paquete debe declarar exactamente una clase NeuroMobileApp.");

        // Android 14+: DexClassLoader lanza SecurityException si el archivo se puede escribir.
        if (!packageFile.setReadOnly()) {
            throw new IOException("No se pudo garantizar que el paquete sea de solo lectura.");
        }

        // Con optimizedDirectory = null, ART gestiona la caché compilada (API 26+).
        DexClassLoader loader = new DexClassLoader(packageFile.getAbsolutePath(), null, null,
                AppLoader.class.getClassLoader());
        String className = meta.classNames.iterator().next();
        Class<?> type = Class.forName(className, true, loader);
        if (!NeuroMobileApp.class.isAssignableFrom(type))
            throw new IOException(className + " no implementa com.neurocenter.api.NeuroMobileApp.");
        NeuroMobileApp app = (NeuroMobileApp) type.getDeclaredConstructor().newInstance();
        if (app.id() == null || !meta.id.equals(app.id())) {
            throw new IOException("El identificador declarado en el manifiesto («" + meta.id
                    + "») no coincide con NeuroMobileApp.id() («" + app.id() + "»).");
        }
        out.add(new LoadedApp(app, name));
    }

    // ------------------------------------------------------------------ metadatos

    private static boolean isApk(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    @SuppressWarnings("deprecation")
    private static Meta readMeta(Context context, File file, boolean apk, String fileName) throws IOException {
        String fallback = fileName;
        int dot = fallback.lastIndexOf('.');
        if (dot > 0) fallback = fallback.substring(0, dot);

        Meta meta = new Meta();
        meta.id = safeId(fallback);
        meta.name = fallback;
        meta.author = "Desconocido";
        meta.version = "Desconocida";

        Set<String> classNames = new LinkedHashSet<>();

        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("classes.dex") == null) {
                throw new IOException("El paquete no contiene classes.dex. Un JAR de escritorio no se puede "
                        + "ejecutar en Android: genera un APK o convierte el JAR con d8.");
            }
            ZipEntry services = zip.getEntry(SERVICES_ENTRY);
            if (services != null) {
                try (InputStream in = zip.getInputStream(services);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int hash = line.indexOf('#');
                        if (hash >= 0) line = line.substring(0, hash);
                        line = line.trim();
                        if (!line.isEmpty()) classNames.add(line);
                    }
                }
            }
        }

        if (apk) {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_META_DATA);
            if (info == null || info.applicationInfo == null) {
                throw new IOException("El archivo no es un APK válido.");
            }
            ApplicationInfo ai = info.applicationInfo;
            ai.sourceDir = file.getAbsolutePath();
            ai.publicSourceDir = file.getAbsolutePath();
            Bundle md = ai.metaData;

            meta.targets = PlatformCompatibility.fromApkMetaData(md);
            meta.id = safeId(metaString(md, META_ID, meta.id));
            meta.author = metaString(md, META_AUTHOR, meta.author);
            if (info.versionName != null && !info.versionName.trim().isEmpty()) meta.version = info.versionName;
            try {
                CharSequence label = ai.loadLabel(pm);
                if (label != null && label.toString().trim().length() > 0
                        && !label.toString().equals(ai.packageName)) {
                    meta.name = label.toString().trim();
                }
            } catch (RuntimeException ignored) {
                // se conserva el nombre de archivo
            }
            addClasses(classNames, metaString(md, META_CLASS, null));
        } else {
            try (JarFile jar = new JarFile(file)) {
                Manifest manifest = jar.getManifest();
                Attributes attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();
                meta.targets = PlatformCompatibility.fromJarManifest(manifest);
                meta.id = safeId(attr(attributes, META_ID, meta.id));
                meta.name = attr(attributes, "Implementation-Title", meta.name);
                meta.author = attr(attributes, "Implementation-Vendor",
                        attr(attributes, "Specification-Vendor", attr(attributes, "Author", meta.author)));
                meta.version = attr(attributes, "Implementation-Version", meta.version);
                addClasses(classNames, attributes.getValue(META_CLASS));
            }
        }

        meta.classNames.addAll(classNames);
        return meta;
    }

    private static void addClasses(Set<String> out, String commaSeparated) {
        if (commaSeparated == null) return;
        for (String part : commaSeparated.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
    }

    @SuppressWarnings("deprecation")
    private static String metaString(Bundle md, String key, String fallback) {
        if (md == null || !md.containsKey(key)) return fallback;
        Object value = md.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String attr(Attributes attributes, String key, String fallback) {
        String value = attributes.getValue(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safeId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return id.isEmpty() ? "external-app" : id;
    }

    private static String safeFileName(String raw) {
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) name = "app";
        return name;
    }

    private static void copyLimited(InputStream in, Path target) throws IOException {
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_PACKAGE_BYTES) throw new IOException("El paquete supera 128 MB.");
                out.write(buffer, 0, n);
            }
        }
        if (total == 0) throw new IOException("El archivo está vacío.");
    }
}
