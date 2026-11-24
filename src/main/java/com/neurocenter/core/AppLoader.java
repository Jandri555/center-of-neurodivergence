package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.NeuroPlatform;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Carga paquetes .ncapp con un ClassLoader Java aislado por archivo.
 *
 * <p>El perfil de permisos se registra en la política de NeuroCenter antes de
 * crear el ClassLoader y, sobre todo, antes de inicializar cualquier clase de
 * la aplicación.</p>
 */
public final class AppLoader {

    public record LoadedApp(NeuroApp app, String jarFilename) {}
    public record Result(List<LoadedApp> apps, List<String> errors) {}

    private AppLoader() {}

    /**
     * Compatibilidad interna para llamadas antiguas: carga solo paquetes .ncapp indicados
     * y les aplica el perfil mínimo sin permisos adicionales.
     */
    public static Result load(Path appsDir, List<String> installedJarNames) {
        Map<String, AppRegistry.AppSecurityProfile> profiles = installedJarNames.stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        name -> new AppRegistry.AppSecurityProfile(fallbackId(name), Set.of()),
                        (left, right) -> left));
        return load(appsDir, profiles);
    }

    /** Carga exclusivamente los paquetes .ncapp registrados y aplica sus permisos concedidos. */
    public static Result load(Path appsDir, Map<String, AppRegistry.AppSecurityProfile> securityProfiles) {
        List<LoadedApp> apps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(appsDir)) return new Result(apps, errors);

        List<Path> jars;
        try (Stream<Path> files = Files.list(appsDir)) {
            jars = files
                    .filter(path -> securityProfiles.containsKey(path.getFileName().toString()))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ncapp"))
                    .sorted()
                    .toList();
        } catch (Exception error) {
            errors.add("No se pudo leer la carpeta de apps: " + error.getMessage());
            return new Result(apps, errors);
        }

        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            URLClassLoader loader = null;
            try {
                AppRegistry.AppSecurityProfile profile = securityProfiles.get(name);
                if (profile == null) continue;

                Path canonicalJar = jar.toRealPath();
                try (JarFile jarFile = new JarFile(canonicalJar.toFile())) {
                    Manifest manifest = jarFile.getManifest();
                    String displayName = manifest == null ? name
                            : manifest.getMainAttributes().getValue("Implementation-Title");
                    if (displayName == null || displayName.isBlank()) displayName = name;
                    PlatformCompatibility.fromAppManifest(manifest)
                            .require(NeuroPlatform.DESKTOP, "la app «" + displayName + "»");

                    if (jarFile.getJarEntry("META-INF/services/com.neurocenter.api.NeuroApp") == null) {
                        throw new IllegalArgumentException("no declara META-INF/services/com.neurocenter.api.NeuroApp");
                    }
                }

                // The live manifest is another hard upper bound on stored grants.
                Set<String> liveDeclared;
                try (JarFile verify = new JarFile(canonicalJar.toFile())) {
                    liveDeclared = AppPermissionManifest.parse(verify.getManifest());
                }
                Set<String> grants = new HashSet<>(profile.grantedPermissions());
                grants.retainAll(profile.declaredPermissions());
                grants.retainAll(liveDeclared);
                // Es esencial registrar primero el CodeSource exacto del JAR.
                NeuroCenterSecurity.registerExternalApp(
                        canonicalJar, profile.appId(), grants);

                URL[] urls = { canonicalJar.toUri().toURL() };
                loader = new URLClassLoader(urls, AppLoader.class.getClassLoader());
                int found = 0;

                try {
                    ServiceLoader<NeuroApp> serviceLoader = ServiceLoader.load(NeuroApp.class, loader);
                    for (NeuroApp app : serviceLoader) {
                        String actualId = app.id();
                        if (actualId == null || !profile.appId().equals(actualId)) {
                            errors.add(name + ": el identificador de NeuroApp («" + actualId
                                    + "») no coincide con NeuroCenter-App-Id («" + profile.appId() + "»).");
                            continue;
                        }
                        if (found > 0) {
                            errors.add(name + ": un .ncapp solo puede registrar una app; instala cada app por separado.");
                            break;
                        }
                        apps.add(new LoadedApp(app, name));
                        found++;
                    }
                } catch (ServiceConfigurationError | SecurityException error) {
                    errors.add(name + ": " + error.getMessage());
                }

                if (found == 0 && errors.stream().noneMatch(error -> error.startsWith(name + ":"))) {
                    errors.add(name + ": no declara ninguna implementación NeuroApp válida.");
                }
            } catch (Throwable error) {
                errors.add(name + ": " + error);
                if (loader != null) {
                    try {
                        loader.close();
                    } catch (Exception ignored) {
                        // El error original es más útil que uno secundario al cerrar.
                    }
                }
            }
        }
        return new Result(apps, errors);
    }

    private static String fallbackId(String filename) {
        String raw = filename.toLowerCase(Locale.ROOT).endsWith(".ncapp")
                ? filename.substring(0, filename.length() - 6) : filename;
        String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return safe.isBlank() ? "external-app" : safe;
    }
}
