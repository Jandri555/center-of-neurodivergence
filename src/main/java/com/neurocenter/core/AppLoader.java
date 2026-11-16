package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.NeuroProgram;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Carga apps externos: cada JAR tiene su propio ClassLoader y puede registrar tanto
 * la API actual (NeuroApp) como la API antigua (NeuroProgram).
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
            int errorsBefore = errors.size();
            Set<String> providerClasses = new HashSet<>();
            int found = 0;

            try {
                URL[] urls = { jar.toUri().toURL() };
                // Se mantiene abierto mientras la app esté activa: sus clases dependen de él.
                URLClassLoader loader = new URLClassLoader(urls, AppLoader.class.getClassLoader());

                // API actual.
                try {
                    Iterator<NeuroApp> current = ServiceLoader.load(NeuroApp.class, loader).iterator();
                    while (current.hasNext()) {
                        NeuroApp app = current.next();
                        if (providerClasses.add(app.getClass().getName())) {
                            apps.add(app);
                            found++;
                        }
                    }
                } catch (ServiceConfigurationError error) {
                    errors.add(name + " (NeuroApp): " + error.getMessage());
                }

                // API anterior: se adapta al contexto y al ciclo de vida actuales.
                try {
                    Iterator<NeuroProgram> legacy = ServiceLoader.load(NeuroProgram.class, loader).iterator();
                    while (legacy.hasNext()) {
                        NeuroProgram program = legacy.next();
                        if (providerClasses.add(program.getClass().getName())) {
                            apps.add(new LegacyProgramAdapter(program));
                            found++;
                        }
                    }
                } catch (ServiceConfigurationError error) {
                    errors.add(name + " (NeuroProgram): " + error.getMessage());
                }

                if (found == 0 && errors.size() == errorsBefore) {
                    errors.add(name + ": no declara ninguna app. Se esperaba "
                            + "META-INF/services/com.neurocenter.api.NeuroApp o "
                            + "META-INF/services/com.neurocenter.api.NeuroProgram.");
                }
            } catch (Throwable error) {
                errors.add(name + ": " + error);
            }
        }
        return new Result(apps, errors);
    }
}
