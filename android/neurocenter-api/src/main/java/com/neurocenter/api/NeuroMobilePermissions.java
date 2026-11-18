package com.neurocenter.api;

import java.util.List;

/**
 * Capacidades que NeuroCenter puede conceder a cada app móvil externa.
 *
 * Las apps integradas son de confianza y reciben estas capacidades implícitamente.
 * Las apps externas parten sin permisos adicionales.
 */
public final class NeuroMobilePermissions {

    public record Permission(String id, String name, String description) {}

    public static final String NETWORK_CONNECT = "network.connect";
    public static final String NETWORK_LISTEN = "network.listen";
    public static final String FILES_READ_DOCUMENTS = "files.read.documents";
    public static final String FILES_WRITE_DOCUMENTS = "files.write.documents";
    public static final String CLIPBOARD_ACCESS = "clipboard.access";
    public static final String CREDENTIALS_ACCESS = "credentials.access";
    public static final String BLUETOOTH_ACCESS = "bluetooth.access";
    public static final String USB_ACCESS = "usb.access";
    public static final String NOTIFICATIONS_ACCESS = "notifications.access";

    private static final List<Permission> SUPPORTED = List.of(
            new Permission(NETWORK_CONNECT, "Conexiones a Internet",
                    "Permite abrir conexiones a servidores y servicios de Internet mediante la API del host."),
            new Permission(NETWORK_LISTEN, "Aceptar conexiones de red",
                    "Permite abrir sockets de servidor mediante la API del host."),
            new Permission(FILES_READ_DOCUMENTS, "Leer documentos del usuario",
                    "Permite solicitar documentos mediante el selector del sistema y leer los URI concedidos."),
            new Permission(FILES_WRITE_DOCUMENTS, "Modificar documentos del usuario",
                    "Permite crear o modificar documentos mediante el selector del sistema."),
            new Permission(CLIPBOARD_ACCESS, "Acceder al portapapeles",
                    "Permite leer y modificar el portapapeles mediante la API del host."),
            new Permission(CREDENTIALS_ACCESS, "Usar el almacén de credenciales",
                    "Permite guardar, consultar y eliminar credenciales propias cifradas con Android Keystore."),
            new Permission(BLUETOOTH_ACCESS, "Usar Bluetooth",
                    "Permite solicitar al host los permisos Bluetooth de Android y utilizar sus API."),
            new Permission(USB_ACCESS, "Acceder a accesorios USB",
                    "Permite solicitar al host acceso a accesorios USB conectados."),
            new Permission(NOTIFICATIONS_ACCESS, "Mostrar notificaciones",
                    "Permite solicitar al host el permiso de notificaciones de Android.")
    );

    private NeuroMobilePermissions() {}

    public static List<Permission> supported() {
        return SUPPORTED;
    }

    public static boolean isSupported(String permissionId) {
        return permissionId != null && SUPPORTED.stream()
                .anyMatch(permission -> permission.id().equals(permissionId));
    }

    public static Permission find(String permissionId) {
        if (permissionId == null) return null;
        return SUPPORTED.stream().filter(permission -> permission.id().equals(permissionId))
                .findFirst().orElse(null);
    }

    public static String summary(Iterable<String> permissionIds) {
        if (permissionIds == null) return "Ninguno";
        java.util.Set<String> ids = new java.util.HashSet<>();
        permissionIds.forEach(ids::add);
        return SUPPORTED.stream().filter(permission -> ids.contains(permission.id()))
                .map(Permission::name).reduce((a, b) -> a + ", " + b).orElse("Ninguno");
    }
}
