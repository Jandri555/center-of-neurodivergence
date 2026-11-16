package com.neurocenter.core;

import com.neurocenter.api.NeuroProgram;

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
 * Carga los programas externos: cada .jar de la carpeta "plugins" se abre con su propio
 * ClassLoader (hijo del de la aplicación, así pueden usar Swing, Gson, PDFBox, FlatLaf...)
 * y se buscan las implementaciones de NeuroProgram declaradas con ServiceLoader.
 */
public final class PluginLoader {

    public record Result(List<NeuroProgram> programs, List<String> errors) {}

    private PluginLoader() {}

    public static Result load(Path pluginsDir) {
        List<NeuroProgram> programs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(pluginsDir)) return new Result(programs, errors);

        List<Path> jars;
        try (Stream<Path> files = Files.list(pluginsDir)) {
            jars = files
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            errors.add("No se pudo leer la carpeta de plugins: " + e.getMessage());
            return new Result(programs, errors);
        }

        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try {
                URL[] urls = { jar.toUri().toURL() };
                // El ClassLoader se mantiene abierto mientras la app esté en marcha (los plugins lo necesitan).
                URLClassLoader loader = new URLClassLoader(urls, PluginLoader.class.getClassLoader());
                Iterator<NeuroProgram> it = ServiceLoader.load(NeuroProgram.class, loader).iterator();
                int found = 0;
                while (it.hasNext()) {
                    programs.add(it.next());
                    found++;
                }
                if (found == 0) {
                    errors.add(name + ": no declara ningún programa "
                            + "(falta META-INF/services/com.neurocenter.api.NeuroProgram)");
                }
            } catch (ServiceConfigurationError e) {
                errors.add(name + ": " + e.getMessage());
            } catch (Throwable t) {
                errors.add(name + ": " + t);
            }
        }
        return new Result(programs, errors);
    }
}
