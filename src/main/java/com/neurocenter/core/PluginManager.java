package com.neurocenter.core;

import com.google.gson.Gson;
import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.NeuroPlatform;
import com.neurocenter.api.plugin.*;
import lombok.Getter;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Gestor de paquetes .ncp. Todo plugin instalado se ejecuta como código de confianza total. */
public final class PluginManager implements AutoCloseable {
    private static final int API_VERSION = 1;
    private final Path directory;
    private final ServiceRegistry services;
    private final EventBus events;
    private final ExtensionRegistry extensions;
    private final Consumer<NeuroApp> appRegistrar;
    private final Gson gson = new Gson();
    @Getter private final List<PluginInfo> plugins = new ArrayList<>();

    public List<PluginInfo> getPlugins() { return List.copyOf(plugins); }
    private final List<LoadedPlugin> loaded = new ArrayList<>();

    public PluginManager(Path directory, ServiceRegistry services, EventBus events,
                         ExtensionRegistry extensions, Consumer<NeuroApp> appRegistrar) {
        this.directory = directory;
        this.services = services;
        this.events = events;
        this.extensions = extensions;
        this.appRegistrar = appRegistrar;
    }

    public synchronized List<String> loadAll() {
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path archive : files.filter(p -> p.toString().endsWith(".ncp")).sorted().toList()) {
                    if (Files.exists(disabledMarker(archive))) continue;
                    try { loadArchive(archive); }
                    catch (Throwable error) { errors.add(archive.getFileName() + ": " + error.getMessage()); }
                }
            }
        } catch (IOException error) {
            errors.add("No se pudo explorar la carpeta de plugins: " + error.getMessage());
        }
        return errors;
    }

    public synchronized void install(Path packageFile) throws IOException {
        if (!packageFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ncp"))
            throw new IOException("El paquete debe tener extensión .ncp.");
        Files.createDirectories(directory);
        Files.copy(packageFile, directory.resolve(packageFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }


    public synchronized List<String> installedPackageNames() {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ncp"))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException error) { return List.of(); }
    }

    public synchronized boolean isEnabled(String packageName) throws IOException {
        return !Files.exists(disabledMarker(safeArchive(packageName)));
    }

    public synchronized void setEnabled(String packageName, boolean enabled) throws IOException {
        Path archive = safeArchive(packageName);
        if (!Files.isRegularFile(archive)) throw new IOException("No existe ese paquete.");
        Path marker = disabledMarker(archive);
        if (enabled) Files.deleteIfExists(marker);
        else Files.writeString(marker, "disabled");
    }

    public synchronized void uninstall(String packageName) throws IOException {
        Path archive = safeArchive(packageName);
        if (!Files.isRegularFile(archive)) throw new IOException("No existe ese paquete.");
        if (plugins.stream().anyMatch(p -> p.packageName().equals(packageName)))
            throw new IOException("El plugin está cargado. Desactívalo, reinicia NeuroCenter y vuelve a desinstalarlo.");
        String pluginId = null;
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("plugin.json")) {
                    Manifest manifest = gson.fromJson(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), Manifest.class);
                    if (manifest != null) pluginId = manifest.id;
                    break;
                }
            }
        }
        Files.deleteIfExists(archive);
        Files.deleteIfExists(disabledMarker(archive));
        if (pluginId != null && pluginId.matches("[A-Za-z0-9._-]{3,120}"))
            deleteTree(directory.resolve(pluginId).normalize());
    }

    private Path safeArchive(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.ncp")) throw new IOException("Nombre de paquete no válido.");
        Path path = directory.resolve(name).normalize();
        if (!path.getParent().equals(directory.normalize())) throw new IOException("Ruta no válida.");
        return path;
    }

    private Path disabledMarker(Path archive) { return directory.resolve(archive.getFileName() + ".disabled"); }

    private void loadArchive(Path archive) throws Exception {
        Path staging = Files.createTempDirectory(directory, ".plugin-stage-");
        try {
            extractSafely(archive, staging);
            Path manifestPath = staging.resolve("plugin.json");
            if (!Files.isRegularFile(manifestPath)) throw new IOException("Falta plugin.json.");
            Manifest manifest = gson.fromJson(Files.readString(manifestPath), Manifest.class);
            validate(manifest);
            Path installDir = directory.resolve(manifest.id).normalize();
            if (!installDir.getParent().equals(directory.normalize())) throw new IOException("ID de plugin no válido.");
            deleteTree(installDir);
            Files.move(staging, installDir, StandardCopyOption.REPLACE_EXISTING);

            Path mainJar = installDir.resolve("plugin.jar");
            if (!Files.isRegularFile(mainJar)) throw new IOException("Falta plugin.jar.");
            List<URL> urls = new ArrayList<>();
            urls.add(mainJar.toUri().toURL());
            NeuroCenterSecurity.registerTrustedPlugin(mainJar);
            Path lib = installDir.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var jars = Files.list(lib)) {
                    for (Path dependency : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                        urls.add(dependency.toUri().toURL());
                        NeuroCenterSecurity.registerTrustedPlugin(dependency);
                    }
                }
            }

            URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), NeuroPlugin.class.getClassLoader());
            try {
                Class<?> entrypoint = Class.forName(manifest.entrypoint, true, loader);
                NeuroPlugin plugin = (NeuroPlugin) entrypoint.getDeclaredConstructor().newInstance();
                plugin.onLoad(new Context(manifest.id));
                plugin.onEnable();
                loaded.add(new LoadedPlugin(manifest.id, plugin, loader));
                plugins.add(new PluginInfo(manifest.id, manifest.name, manifest.version, archive.getFileName().toString()));
                events.publish(new PluginLoadedEvent(manifest.id, manifest.version));
            } catch (Throwable error) {
                loader.close();
                throw error;
            }
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    private static void validate(Manifest manifest) throws IOException {
        if (manifest == null || manifest.id == null || !manifest.id.matches("[A-Za-z0-9._-]{3,120}"))
            throw new IOException("Identificador de plugin ausente o no válido.");
        if (manifest.entrypoint == null || manifest.entrypoint.isBlank()) throw new IOException("Falta entrypoint.");
        if (manifest.apiVersion != API_VERSION) throw new IOException("Versión de API incompatible: " + manifest.apiVersion);
        if (manifest.name == null || manifest.name.isBlank()) manifest.name = manifest.id;
        if (manifest.version == null || manifest.version.isBlank()) manifest.version = "0.0.0";
    }

    private static void extractSafely(Path archive, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination.normalize())) throw new IOException("Ruta no permitida dentro del paquete.");
                if (entry.isDirectory()) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    @Override public synchronized void close() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedPlugin item = loaded.get(i);
            try { item.plugin.onDisable(); } catch (Throwable ignored) {}
            try { item.plugin.onUnload(); } catch (Throwable ignored) {}
            try { item.loader.close(); } catch (IOException ignored) {}
        }
        loaded.clear();
    }

    private final class Context implements PluginContext {
        private final String id;
        Context(String id) { this.id = id; }
        public String pluginId() { return id; }
        public Path dataDirectory() { return PluginPaths.dataDir(id); }
        public NeuroPlatform platform() { return NeuroPlatform.DESKTOP; }
        public ServiceRegistry services() { return services; }
        public EventBus events() { return events; }
        public ExtensionRegistry extensions() { return extensions; }
        public void registerApplication(NeuroApp app) { appRegistrar.accept(app); }
    }

    private static final class Manifest { String id, name, version, entrypoint; int apiVersion; }
    private record LoadedPlugin(String id, NeuroPlugin plugin, URLClassLoader loader) {}
    public record PluginInfo(String id, String name, String version, String packageName) {}
    public record PluginLoadedEvent(String pluginId, String version) {}
    public record ApplicationStartedEvent(String appId, String appName) {}
    public record ApplicationStoppedEvent(String appId, String appName) {}
}
