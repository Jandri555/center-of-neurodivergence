package com.neurocenter.core;

import com.neurocenter.api.AppStorage;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

/**
 * Implementación del acceso a archivos para una app concreta.
 *
 * Los permisos permanentes viven en SQLite; los permisos temporales solo
 * duran mientras la sesión de la app permanezca abierta.
 */
public final class ManagedAppStorage implements AppStorage {

    private static final String ONCE = "ONCE";
    private static final String CANCEL = "CANCEL";

    private final String appId;
    private final String appName;
    private final Path dataDirectory;
    private final AppRegistry registry;
    private final Window parent;
    private final boolean permissionRequired;
    private final Set<Path> sessionFolders = new LinkedHashSet<>();
    private boolean sessionClosed;

    public ManagedAppStorage(
            String appId,
            String appName,
            Path dataDirectory,
            AppRegistry registry,
            Window parent,
            boolean permissionRequired) {
        this.appId = appId;
        this.appName = appName;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.registry = registry;
        this.parent = parent;
        this.permissionRequired = permissionRequired;
    }

    /** Revoca los permisos temporales cuando termina la sesión de la app. */
    public synchronized void closeSession() {
        sessionClosed = true;
        sessionFolders.clear();
    }

    @Override
    public boolean exists(Path path) throws IOException {
        Path resolved = resolve(path);
        authorize(resolved, "comprobar si existe", false);
        return Files.exists(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public List<Path> list(Path directory) throws IOException {
        Path resolved = resolve(directory);
        authorize(resolved, "listar el contenido", true);
        try (Stream<Path> entries = Files.list(resolved)) {
            return entries.sorted().toList();
        }
    }

    @Override
    public InputStream openRead(Path file) throws IOException {
        Path resolved = resolve(file);
        authorize(resolved, "leer archivos", false);
        return Files.newInputStream(resolved);
    }

    @Override
    public OutputStream openWrite(Path file) throws IOException {
        Path resolved = resolve(file);
        authorize(resolved, "escribir archivos", false);
        return Files.newOutputStream(resolved,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    @Override
    public void createDirectories(Path directory) throws IOException {
        Path resolved = resolve(directory);
        authorize(resolved, "crear carpetas", true);
        Files.createDirectories(resolved);
    }

    @Override
    public void delete(Path path) throws IOException {
        Path resolved = resolve(path);
        authorize(resolved, "eliminar archivos o carpetas", Files.isDirectory(resolved));
        Files.delete(resolved);
    }

    private Path resolve(Path path) {
        if (path == null) throw new IllegalArgumentException("La ruta no puede ser null.");
        return (path.isAbsolute() ? path : dataDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private void authorize(Path requested, String operation, boolean directoryOperation) throws IOException {
        if (!permissionRequired) return;

        Path realTarget = resolveExistingPrefix(requested);
        Path realPrivateDirectory = dataDirectory.toRealPath();
        if (realTarget.startsWith(realPrivateDirectory)) return;

        synchronized (this) {
            if (sessionClosed) throw denied(requested, "La sesión de la aplicación ya ha terminado.");

            if (isInsideAny(realTarget, sessionFolders)) return;

            AppRegistry.PermissionRecord saved;
            try {
                saved = registry.getPermission(appId, AppRegistry.STORAGE_PERMISSION);
            } catch (SQLException ex) {
                throw new IOException("No se pudo consultar el permiso de almacenamiento.", ex);
            }

            if (saved != null) {
                switch (saved.decision()) {
                    case AppRegistry.ALLOW_ALWAYS -> {
                        return;
                    }
                    case AppRegistry.DENY_ALWAYS ->
                            throw denied(requested, "La aplicación tiene el acceso al almacenamiento denegado.");
                    case AppRegistry.LIMITED_FOLDERS -> {
                        if (isInsideSavedFolders(realTarget, saved.folders())) return;
                        throw denied(requested,
                                "La ruta está fuera de las carpetas autorizadas para esta aplicación.");
                    }
                    default -> throw new IOException("El registro contiene una decisión de permiso desconocida.");
                }
            }

            PromptResult choice = onEdt(() -> askUser(realTarget, operation));
            switch (choice.decision()) {
                case ONCE -> {
                    Path sessionRoot = directoryOperation || Files.isDirectory(requested)
                            ? realTarget : realTarget.getParent();
                    if (sessionRoot == null) sessionRoot = realTarget;
                    sessionFolders.add(sessionRoot);
                }
                case AppRegistry.ALLOW_ALWAYS -> saveDecision(AppRegistry.ALLOW_ALWAYS, List.of());
                case AppRegistry.DENY_ALWAYS -> {
                    saveDecision(AppRegistry.DENY_ALWAYS, List.of());
                    throw denied(requested, "El usuario ha denegado permanentemente el acceso al almacenamiento.");
                }
                case AppRegistry.LIMITED_FOLDERS -> {
                    List<String> folders = choice.folders().stream().map(Path::toString).toList();
                    saveDecision(AppRegistry.LIMITED_FOLDERS, folders);
                    if (!isInsideAny(realTarget, new LinkedHashSet<>(choice.folders()))) {
                        throw denied(requested,
                                "La ruta solicitada no está dentro de las carpetas que se han autorizado.");
                    }
                }
                default -> throw denied(requested, "No se ha concedido el permiso de almacenamiento.");
            }
        }
    }

    private void saveDecision(String decision, List<String> folders) throws IOException {
        try {
            registry.savePermission(appId, AppRegistry.STORAGE_PERMISSION, decision, folders);
        } catch (SQLException ex) {
            throw new IOException("No se pudo guardar la decisión del permiso.", ex);
        }
    }

    private PromptResult askUser(Path requested, String operation) {
        String[] options = {
                "Permitir una vez",
                "Permitir siempre",
                "No volver a preguntar",
                "Solo en carpetas elegidas…"
        };
        String message = appName + " solicita permiso para " + operation
                + " fuera de su carpeta privada.\n\nRuta solicitada:\n"
                + requested
                + "\n\n«Permitir una vez» autoriza esta carpeta durante la sesión actual.";
        int answer = JOptionPane.showOptionDialog(
                parent,
                message,
                "Permiso de almacenamiento",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);

        if (answer == 0) return new PromptResult(ONCE, List.of());
        if (answer == 1) return new PromptResult(AppRegistry.ALLOW_ALWAYS, List.of());
        if (answer == 2) return new PromptResult(AppRegistry.DENY_ALWAYS, List.of());
        if (answer != 3) return new PromptResult(CANCEL, List.of());

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Carpetas a las que puede acceder " + appName);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return new PromptResult(CANCEL, List.of());
        }

        Set<Path> selected = new LinkedHashSet<>();
        java.io.File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) {
            java.io.File single = chooser.getSelectedFile();
            if (single != null) files = new java.io.File[]{single};
        }
        if (files != null) {
            for (java.io.File file : files) {
                try {
                    Path folder = file.toPath().toRealPath();
                    if (Files.isDirectory(folder)) selected.add(folder);
                } catch (IOException ignored) {
                    // Una carpeta que ya no existe no se concede.
                }
            }
        }

        if (selected.isEmpty()) return new PromptResult(CANCEL, List.of());
        if (!isInsideAny(requested, selected)) {
            JOptionPane.showMessageDialog(
                    parent,
                    "La ruta solicitada no está dentro de ninguna de las carpetas elegidas. "
                            + "No se ha concedido el permiso.",
                    "Carpetas no válidas",
                    JOptionPane.WARNING_MESSAGE);
            return new PromptResult(CANCEL, List.of());
        }
        return new PromptResult(AppRegistry.LIMITED_FOLDERS, List.copyOf(selected));
    }

    private boolean isInsideSavedFolders(Path target, List<String> folders) {
        for (String folderText : folders) {
            try {
                Path folder = Path.of(folderText);
                if (Files.isDirectory(folder) && target.startsWith(folder.toRealPath())) return true;
            } catch (Exception ignored) {
                // Si la carpeta ya no existe o la ruta guardada no es válida, no se concede.
            }
        }
        return false;
    }

    private static boolean isInsideAny(Path target, Set<Path> folders) {
        for (Path folder : folders) {
            if (target.startsWith(folder)) return true;
        }
        return false;
    }

    /**
     * Resuelve enlaces simbólicos en la parte existente de la ruta. Para
     * archivos aún no creados, conserva los segmentos finales desde el último
     * directorio existente. Así no se puede escapar de una carpeta autorizada
     * utilizando ".." o un enlace simbólico.
     */
    private static Path resolveExistingPrefix(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            Path name = current.getFileName();
            if (name != null) missing.add(name);
            current = current.getParent();
        }
        if (current == null) throw new IOException("No se pudo resolver la ruta solicitada: " + path);

        Path resolved = current.toRealPath();
        for (int i = missing.size() - 1; i >= 0; i--) {
            resolved = resolved.resolve(missing.get(i));
        }
        return resolved.normalize();
    }

    private static AccessDeniedException denied(Path path, String reason) {
        return new AccessDeniedException(path.toString(), null, reason);
    }

    private static <T> T onEdt(Callable<T> action) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return action.call();
            } catch (Exception ex) {
                if (ex instanceof IOException io) throw io;
                throw new IOException("No se pudo mostrar la solicitud de permiso.", ex);
            }
        }

        FutureTask<T> task = new FutureTask<>(action);
        try {
            SwingUtilities.invokeAndWait(task);
            return task.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpió la solicitud de permiso.", ex);
        } catch (java.lang.reflect.InvocationTargetException | ExecutionException ex) {
            throw new IOException("No se pudo mostrar la solicitud de permiso.", ex);
        }
    }

    private record PromptResult(String decision, List<Path> folders) {}
}
