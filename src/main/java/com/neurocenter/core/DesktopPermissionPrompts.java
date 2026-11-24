package com.neurocenter.core;

import com.neurocenter.api.NeuroApp;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/**
 * Trusted, modal permission broker. A request is valid only for a capability
 * declared at installation in the .ncapp manifest. User decisions:
 * Allow/Deny for this session, Always Allow/Always Deny persisted in SQLite.
 */
final class DesktopPermissionPrompts {
    private final MainWindow window;
    private final AppRegistry registry;
    private final Map<String, Set<String>> sessionAllowed = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionDenied = new ConcurrentHashMap<>();

    DesktopPermissionPrompts(MainWindow window, AppRegistry registry) {
        this.window = window;
        this.registry = registry;
    }

    boolean has(NeuroApp app, String permission) {
        if (!registryDeclared(app.id(), permission)) return false;
        try {
            Boolean decision = registry.storedDecision(app.id(), permission);
            return Boolean.TRUE.equals(decision)
                    || (decision == null && sessionAllowed.getOrDefault(app.id(), Set.of()).contains(permission));
        } catch (SQLException ex) {
            throw new SecurityException("No se pudo comprobar el permiso.", ex);
        }
    }

    boolean request(NeuroApp app, String jarFilename, String permission) {
        // Must reject an undeclared permission BEFORE any user-facing dialog.
        if (!NeuroCenterSecurity.isSupportedPermission(permission)
                || !registryDeclared(app.id(), permission))
            throw new SecurityException("La aplicación «" + app.name()
                    + "» solicita un permiso que no declara: " + permission);
        try {
            Boolean saved = registry.storedDecision(app.id(), permission);
            if (saved != null) {
                if (saved) refreshPolicy(app, jarFilename);
                return saved;
            }
            if (sessionAllowed.getOrDefault(app.id(), Set.of()).contains(permission)) return true;
            if (sessionDenied.getOrDefault(app.id(), Set.of()).contains(permission)) return false;

            NeuroCenterSecurity.AppPermission description = NeuroCenterSecurity.supportedPermissions()
                    .stream().filter(p -> p.id().equals(permission)).findFirst().orElseThrow();
            Object[] options = {"Permitir", "Denegar", "Permitir siempre", "Denegar siempre"};
            FutureTask<Integer> prompt = new FutureTask<>(() ->
                JOptionPane.showOptionDialog(window,
                    "La aplicación «" + app.name() + "» solicita el permiso:\n\n"
                            + description.name() + " (" + permission + ")\n"
                            + description.description() + "\n\n"
                            + "«Permitir» y «Denegar» se aplican a esta sesión.",
                    "Permiso solicitado por «" + app.name() + "»",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[1]));
            if (SwingUtilities.isEventDispatchThread()) prompt.run();
            else SwingUtilities.invokeAndWait(prompt);
            int selection = prompt.get();
            switch (selection) {
                case 0 -> sessionAllowed.computeIfAbsent(app.id(),
                        ignored -> ConcurrentHashMap.newKeySet()).add(permission);
                case 1 -> sessionDenied.computeIfAbsent(app.id(),
                        ignored -> ConcurrentHashMap.newKeySet()).add(permission);
                case 2 -> registry.setDecision(app.id(), permission, true);
                case 3 -> registry.setDecision(app.id(), permission, false);
                default -> { return false; }
            }
            if (selection == 0 || selection == 2) {
                refreshPolicy(app, jarFilename);
                return true;
            }
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            throw new SecurityException("No se pudo solicitar el permiso.", ex);
        }
    }

    void refreshPolicy(NeuroApp app, String jarFilename) {
        if (jarFilename == null) return; // Built-in apps are trusted.
        try {
            Set<String> granted = new java.util.HashSet<>(registry.grantedPermissions(app.id()));
            Set<String> declared = registry.declaredPermissions(app.id());
            for (String permission : sessionAllowed.getOrDefault(app.id(), Set.of())) {
                if (declared.contains(permission) && registry.storedDecision(app.id(), permission) == null)
                    granted.add(permission);
            }
            granted.retainAll(declared);
            Path jar = AppPaths.appsDir().resolve(jarFilename).toRealPath();
            NeuroCenterSecurity.registerExternalApp(jar, app.id(), granted);
        } catch (Exception ex) {
            throw new SecurityException("No se pudo actualizar la política de seguridad.", ex);
        }
    }

    void close(NeuroApp app, String jarFilename) {
        sessionAllowed.remove(app.id());
        sessionDenied.remove(app.id());
        refreshPolicy(app, jarFilename);
    }

    private boolean registryDeclared(String appId, String permission) {
        try {
            return registry.declaredPermissions(appId).contains(permission);
        } catch (SQLException ex) {
            throw new SecurityException("No se pudo leer el manifiesto de permisos.", ex);
        }
    }
}
