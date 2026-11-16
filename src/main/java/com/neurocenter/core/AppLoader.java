package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Carga los apps externos: cada .jar de la carpeta "apps" se abre con su propio
 * ClassLoader (hijo del de la aplicación, así pueden usar Swing, Gson, PDFBox, FlatLaf...)
 * y se buscan las implementaciones de NeuroApp declaradas con ServiceLoader.
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
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
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
                // El ClassLoader se mantiene abierto mientras la app esté en marcha (los apps lo necesitan).
                URLClassLoader loader = new URLClassLoader(urls, AppLoader.class.getClassLoader());
                Iterator<NeuroApp> it = ServiceLoader.load(NeuroApp.class, loader).iterator();
                int found = 0;
                while (it.hasNext()) {
                    apps.add(it.next());
                    found++;
                }
                if (found == 0) {
                    errors.add(name + ": no declara ningún app "
                            + "(falta META-INF/services/com.neurocenter.api.NeuroApp)");
                }
            } catch (ServiceConfigurationError e) {
                errors.add(name + ": " + e.getMessage());
            } catch (Throwable t) {
                errors.add(name + ": " + t);
            }
        }
        return new Result(apps, errors);
    }
}
