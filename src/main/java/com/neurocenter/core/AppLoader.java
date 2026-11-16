package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Carga apps externas: cada JAR se abre con su propio ClassLoader y se buscan
 * las implementaciones de NeuroApp declaradas mediante ServiceLoader.
 */
public final class AppLoader {

    public record Result(List<NeuroApp> apps, List<String> errors) {}

    private AppLoader() {}

    public static Result load(Path appsDir) {
        List<NeuroApp> apps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(appsDir)) return new Result(apps, errors);

        List<Path> jars;
        try (Stream<Path> files = Files.list(appsDir)) {
            jars = files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            errors.add("No se pudo leer la carpeta de apps: " + e.getMessage());
            return new Result(apps, errors);
        }

        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try {
                URL[] urls = { jar.toUri().toURL() };
                // El ClassLoader permanece abierto mientras la app está en uso.
                URLClassLoader loader = new URLClassLoader(urls, AppLoader.class.getClassLoader());
                int found = 0;
                try {
                    ServiceLoader<NeuroApp> serviceLoader = ServiceLoader.load(NeuroApp.class, loader);
                    for (NeuroApp app : serviceLoader) {
                        apps.add(app);
                        found++;
                    }
                } catch (ServiceConfigurationError error) {
                    errors.add(name + ": " + error.getMessage());
                }
                if (found == 0 && errors.stream().noneMatch(error -> error.startsWith(name + ":"))) {
                    errors.add(name + ": no declara ninguna app. Falta "
                            + "META-INF/services/com.neurocenter.api.NeuroApp.");
                }
            } catch (Throwable error) {
                errors.add(name + ": " + error);
            }
        }
        return new Result(apps, errors);
    }
}
