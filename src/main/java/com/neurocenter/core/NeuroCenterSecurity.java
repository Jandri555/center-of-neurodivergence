package com.neurocenter.core;

import java.awt.AWTPermission;
import java.io.FilePermission;
import java.net.SocketPermission;
import java.net.URL;
import java.net.URLPermission;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.AllPermission;
import java.security.AccessControlContext;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.SecurityPermission;
import java.util.PropertyPermission;

/**
 * Instala y administra el modelo de permisos de las aplicaciones externas.
 *
 * <p>El código integrado de NeuroCenter conserva permisos completos. Cada JAR
 * externo recibe únicamente acceso a su propio JAR, a su directorio privado
 * de datos y a las capacidades que el usuario haya concedido.</p>
 *
 * <p>Este mecanismo depende del Security Manager de Java 21. No constituye un
 * aislamiento frente a código nativo malicioso ni frente a vulnerabilidades
 * de la JVM; por eso las apps externas siguen siendo código de confianza
 * limitada, no código hostil completamente aislado.</p>
 */
@SuppressWarnings("removal")
public final class NeuroCenterSecurity {

    public record AppPermission(String id, String name, String description) {}

    private static final List<AppPermission> SUPPORTED_PERMISSIONS = List.of(
            new AppPermission("network.connect", "Conexiones a Internet",
                    "Permite conectarse a servidores y servicios de Internet."),
            new AppPermission("network.listen", "Aceptar conexiones de red",
                    "Permite abrir puertos y aceptar conexiones entrantes."),
            new AppPermission("files.read.documents", "Leer documentos del usuario",
                    "Permite leer archivos de Documentos, Escritorio y Descargas."),
            new AppPermission("files.write.documents", "Modificar documentos del usuario",
                    "Permite crear, modificar y eliminar archivos en Documentos, Escritorio y Descargas."),
            new AppPermission("clipboard.access", "Acceder al portapapeles",
                    "Permite leer y modificar el portapapeles del sistema.")
    );

    private static volatile NeuroCenterPolicy policy;

    private NeuroCenterSecurity() {}

    /**
     * Debe ejecutarse al principio del arranque, antes de cargar apps externas.
     * El lanzador Java 21 se inicia con -Djava.security.manager=allow para
     * permitir esta instalación controlada durante el arranque.
     */
    public static synchronized void install() {
        if (System.getSecurityManager() != null) {
            throw new IllegalStateException(
                    "El Security Manager ya estaba instalado antes de configurar la política de NeuroCenter.");
        }

        String managerMode = System.getProperty("java.security.manager");
        if (!"allow".equals(managerMode)) {
            throw new IllegalStateException(
                    "El runtime debe iniciarse con -Djava.security.manager=allow. "
                            + "El instalador de NeuroCenter ya incluye esta opción.");
        }

        CodeSource hostCodeSource = NeuroCenterSecurity.class.getProtectionDomain().getCodeSource();
        if (hostCodeSource == null || hostCodeSource.getLocation() == null) {
            throw new IllegalStateException("No se pudo identificar el código de confianza de NeuroCenter.");
        }

        Policy basePolicy = Policy.getPolicy();
        NeuroCenterPolicy guardedPolicy = new NeuroCenterPolicy(
                basePolicy, hostCodeSource.getLocation(), AppPaths.appsDir());
        Policy.setPolicy(guardedPolicy);
        policy = guardedPolicy;

        System.setSecurityManager(new SecurityManager());
        if (System.getSecurityManager() == null) {
            throw new IllegalStateException("Java no ha activado el Security Manager.");
        }
    }

    /** Registra los permisos efectivos de un JAR antes de cargar sus clases. */
    static void registerExternalApp(Path jar, String appId, Set<String> grantedPermissions) throws Exception {
        NeuroCenterPolicy active = policy;
        if (active == null || System.getSecurityManager() == null) {
            throw new IllegalStateException("La seguridad de las apps no se ha inicializado.");
        }

        Path canonicalJar = jar.toRealPath();
        Path dataDirectory = AppPaths.appDataDir(appId);
        active.registerApp(canonicalJar, dataDirectory, grantedPermissions);
    }

    /**
     * Smoke test usado por CI. Comprueba la instalación dinámica en Java 21 y
     * que una ProtectionDomain de app no puede leer fuera de su carpeta privada,
     * abrir red, cargar clases dinámicamente ni retirar el Security Manager.
     */
    public static void runSelfTest() throws Exception {
        if (System.getSecurityManager() == null || policy == null) {
            throw new IllegalStateException("Security Manager o política de NeuroCenter no instalados.");
        }

        String testId = "security-self-test-" + UUID.randomUUID();
        Path testJar = AppPaths.appsDir().resolve(testId + ".jar");
        Path privateDirectory = AppPaths.appDataDir(testId);
        Path allowedFile = privateDirectory.resolve("allowed.txt");
        Path forbiddenFile = AppPaths.dataDir().resolve(testId + "-forbidden.txt");

        try {
            Files.writeString(testJar, "test jar");
            Files.writeString(allowedFile, "private data");
            Files.writeString(forbiddenFile, "outside data");

            registerExternalApp(testJar, testId, Set.of());

            CodeSource codeSource = new CodeSource(
                    testJar.toRealPath().toUri().toURL(),
                    (java.security.cert.Certificate[]) null);
            ProtectionDomain appDomain = new ProtectionDomain(
                    codeSource, null, ClassLoader.getSystemClassLoader(), null);
            AccessControlContext appContext = new AccessControlContext(
                    new ProtectionDomain[]{appDomain});

            String contents = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> {
                        try {
                            return Files.readString(allowedFile);
                        } catch (java.io.IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }, appContext);
            if (!"private data".equals(contents)) {
                throw new IllegalStateException("La app de prueba no pudo leer sus propios datos.");
            }

            expectDenied(appContext, new FilePermission(forbiddenFile.toString(), "read"));
            expectDenied(appContext, new SocketPermission("example.com:443", "connect"));
            expectDenied(appContext, new RuntimePermission("createClassLoader"));
            expectDenied(appContext, new RuntimePermission("loadLibrary.neurocenter_test"));
            expectDenied(appContext, new RuntimePermission("setSecurityManager"));
            expectDenied(appContext, new SecurityPermission("setPolicy"));
        } finally {
            Files.deleteIfExists(allowedFile);
            Files.deleteIfExists(forbiddenFile);
            Files.deleteIfExists(testJar);
            Files.deleteIfExists(privateDirectory);
        }
    }

    private static void expectDenied(AccessControlContext context, Permission permission) {
        try {
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                AccessController.checkPermission(permission);
                return null;
            }, context);
        } catch (AccessControlException expected) {
            return;
        }
        throw new IllegalStateException("Una operación que debía bloquearse recibió permiso: " + permission);
    }

    public static List<AppPermission> supportedPermissions() {
        return SUPPORTED_PERMISSIONS;
    }

    public static boolean isSupportedPermission(String permissionId) {
        return SUPPORTED_PERMISSIONS.stream().anyMatch(permission -> permission.id().equals(permissionId));
    }

    static String permissionSummary(Set<String> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) return "Ninguno";
        return SUPPORTED_PERMISSIONS.stream()
                .filter(permission -> permissionIds.contains(permission.id()))
                .map(AppPermission::name)
                .reduce((left, right) -> left + ", " + right)
                .orElse("Ninguno");
    }

    static final class NeuroCenterPolicy extends Policy {
        private final Policy delegate;
        private final String hostLocation;
        private final Path appsDirectory;
        private final java.util.concurrent.ConcurrentMap<String, PermissionCollection> appPermissions =
                new java.util.concurrent.ConcurrentHashMap<>();

        NeuroCenterPolicy(Policy delegate, URL hostLocation, Path appsDirectory) {
            this.delegate = delegate;
            this.hostLocation = locationKey(hostLocation);
            this.appsDirectory = appsDirectory.toAbsolutePath().normalize();
        }

        void registerApp(Path jar, Path dataDirectory, Set<String> grantedPermissions) throws Exception {
            Path canonicalJar = jar.toRealPath();
            Set<String> safeGrants = new LinkedHashSet<>();
            if (grantedPermissions != null) {
                for (String permission : grantedPermissions) {
                    if (!isSupportedPermission(permission)) {
                        throw new IllegalArgumentException("Permiso no reconocido: " + permission);
                    }
                    safeGrants.add(permission);
                }
            }

            Permissions permissions = new Permissions();
            permissions.add(new FilePermission(canonicalJar.toString(), "read"));
            permissions.add(new FilePermission(dataDirectory.toString(), "read,write"));
            permissions.add(new FilePermission(
                    dataDirectory.resolve("-").toString(), "read,write,delete"));

            // Propiedades de plataforma habituales. No se concede acceso general
            // a System.getProperties() ni a las variables de entorno.
            for (String property : List.of(
                    "java.version", "java.vendor", "java.specification.version",
                    "os.name", "os.version", "os.arch",
                    "file.encoding", "file.separator", "path.separator", "line.separator",
                    "user.language", "user.country", "user.timezone", "java.io.tmpdir")) {
                permissions.add(new PropertyPermission(property, "read"));
            }

            // Permisos mínimos para que una app pueda actualizar su interfaz Swing.
            permissions.add(new AWTPermission("accessEventQueue"));
            permissions.add(new AWTPermission("showWindowWithoutWarningBanner"));

            if (safeGrants.contains("network.connect")) {
                permissions.add(new SocketPermission("*", "connect,resolve"));
                permissions.add(new URLPermission("http://*/*", "*"));
                permissions.add(new URLPermission("https://*/*", "*"));
            }
            if (safeGrants.contains("network.listen")) {
                permissions.add(new SocketPermission("*", "listen,accept,resolve"));
            }
            if (safeGrants.contains("files.read.documents")
                    || safeGrants.contains("files.write.documents")) {
                String actions = safeGrants.contains("files.read.documents") ? "read" : "";
                if (safeGrants.contains("files.write.documents")) {
                    actions = actions.isEmpty() ? "write,delete" : actions + ",write,delete";
                }
                for (Path directory : userDocumentDirectories()) {
                    permissions.add(new FilePermission(directory.resolve("-").toString(), actions));
                    permissions.add(new FilePermission(directory.toString(), actions));
                }
            }
            if (safeGrants.contains("clipboard.access")) {
                permissions.add(new AWTPermission("accessClipboard"));
            }

            permissions.setReadOnly();
            appPermissions.put(locationKey(canonicalJar.toUri().toURL()), permissions);
        }

        @Override
        public boolean implies(ProtectionDomain domain, Permission permission) {
            if (domain == null) return false;
            URL location = domain.getCodeSource() == null ? null : domain.getCodeSource().getLocation();
            if (location == null) return delegate.implies(domain, permission);

            String key = locationKey(location);
            if (hostLocation.equals(key)) return true;

            PermissionCollection appGrant = appPermissions.get(key);
            if (appGrant != null) return appGrant.implies(permission);

            // Un registro ausente dentro de apps/ se trata como denegación,
            // nunca como permiso heredado de la política global del usuario.
            if (isInsideAppsDirectory(location)) return false;
            return delegate.implies(domain, permission);
        }

        @Override
        public PermissionCollection getPermissions(ProtectionDomain domain) {
            if (domain == null) return new Permissions();
            URL location = domain.getCodeSource() == null ? null : domain.getCodeSource().getLocation();
            if (location == null) return delegate.getPermissions(domain);
            return permissionsFor(location, domain);
        }

        @Override
        public PermissionCollection getPermissions(CodeSource codeSource) {
            if (codeSource == null || codeSource.getLocation() == null) {
                return new Permissions();
            }
            return permissionsFor(codeSource.getLocation(), null);
        }

        @Override
        public void refresh() {
            delegate.refresh();
        }

        private PermissionCollection permissionsFor(URL location, ProtectionDomain domain) {
            String key = locationKey(location);
            if (hostLocation.equals(key)) return allPermissions();

            PermissionCollection appGrant = appPermissions.get(key);
            if (appGrant != null) return appGrant;
            if (isInsideAppsDirectory(location)) return new Permissions();

            return domain == null
                    ? delegate.getPermissions(new CodeSource(location, (java.security.cert.Certificate[]) null))
                    : delegate.getPermissions(domain);
        }

        private boolean isInsideAppsDirectory(URL location) {
            try {
                if (!"file".equalsIgnoreCase(location.getProtocol())) return false;
                Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
                return path.startsWith(appsDirectory);
            } catch (Exception ignored) {
                return false;
            }
        }

        private static PermissionCollection allPermissions() {
            Permissions permissions = new Permissions();
            permissions.add(new AllPermission());
            permissions.setReadOnly();
            return permissions;
        }

        private static String locationKey(URL url) {
            try {
                if ("file".equalsIgnoreCase(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().normalize().toUri().toString();
                }
            } catch (Exception ignored) {
                // Se conserva la URL original si no se puede convertir en Path.
            }
            return url.toExternalForm();
        }

        private static List<Path> userDocumentDirectories() {
            Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            List<Path> result = new ArrayList<>();
            result.add(home.resolve("Documents"));
            result.add(home.resolve("Desktop"));
            result.add(home.resolve("Downloads"));
            return result;
        }
    }
}
