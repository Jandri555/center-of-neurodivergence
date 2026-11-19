# Aplicaciones externas en Android

## Decisión de seguridad

Las aplicaciones externas de NeuroCenter para Android son **APK instalados por Android como paquetes independientes**. No son JAR ni DEX que NeuroCenter copie a su carpeta privada y cargue con `DexClassLoader`.

Android asigna una identidad Linux/UID y un entorno privado a cada paquete. Por tanto:

- Cada app declara los permisos que necesita en su propio `AndroidManifest.xml`.
- Cada app solicita sus permisos de ejecución desde su propio proceso y recibe el resultado de Android.
- NeuroCenter no puede conceder permisos Android a otra app ni utilizar sus permisos en nombre de ella.
- Una app no recibe acceso a los datos privados de NeuroCenter ni a los datos privados de otra app.
- La instalación, actualización y desinstalación pasan por el Package Manager de Android y las confirmaciones del usuario.

No se debe usar `DexClassLoader` para ejecutar APK/JAR de terceros dentro del proceso de NeuroCenter. Un wrapper de permisos de la API no es una frontera de seguridad: el código cargado comparte UID, memoria y permisos del proceso anfitrión.

## Contrato de descubrimiento

Una app compatible publica una Activity de entrada exportada que declara el filtro:

```xml
<activity
    android:name=".NeuroCenterEntryActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="com.neurocenter.action.OPEN_APP" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Opcionalmente puede incluir metadatos dentro de `<application>`:

```xml
<meta-data
    android:name="com.neurocenter.app.id"
    android:value="mi-aplicacion" />
<meta-data
    android:name="com.neurocenter.app.description"
    android:value="Descripción breve de la aplicación" />
```

El identificador debe ser estable y contener solo letras, números, punto, guion o guion bajo. Si se omite, NeuroCenter utiliza el nombre del paquete Android como identificador.

NeuroCenter declara en `<queries>` únicamente la acción `com.neurocenter.action.OPEN_APP`. No solicita `QUERY_ALL_PACKAGES`: solo descubre paquetes que anuncian el contrato. La clase `AndroidExternalApps` implementa el descubrimiento, el lanzamiento explícito y la entrega opcional de un APK al instalador del sistema.

## Dos capas distintas de permisos

### Permisos Android del paquete

Los gestiona Android. Por ejemplo, una app que necesita Bluetooth declara `BLUETOOTH_CONNECT` en su manifiesto y solicita el permiso en tiempo de ejecución desde su propia Activity. NeuroCenter no debe solicitarlo por ella ni pasarle su `Activity` o `Context`.

Para documentos se debe preferir el Storage Access Framework (selector de documentos) y los URI concedidos por el sistema, en vez de pedir acceso general al almacenamiento.

### Capacidades de NeuroCenter

Son permisos propios, independientes de Android. Controlarán operaciones que la app solo pueda realizar a través de servicios de NeuroCenter, como leer o modificar la Agenda, utilizar el almacén de credenciales del centro o acceder a datos compartidos.

La futura API de servicios debe exponerse mediante IPC/Binder y comprobar siempre la identidad del llamante usando el UID real obtenido de Binder y su paquete instalado. Nunca debe aceptar un `packageName` enviado libremente en un Intent/Bundle como prueba de identidad. Las concesiones se guardarán por paquete y capacidad y se comprobarán en cada operación.

**Estado:** están implementados el descubrimiento y lanzamiento de paquetes independientes, el paso de APK al instalador del sistema, una pantalla de lista y un gestor de concesiones por paquete. También existe un servicio Binder que comprueba el UID real del llamante y aplica las capacidades en el servidor para Agenda y credenciales. Aún faltan pruebas de integración en dispositivo, revisión del flujo de ciclo de vida y la migración del shell móvil completo; no se debe presentar el modelo como listo para producción hasta completar esas pruebas.

## Instalación desde NeuroCenter

La instalación de APK utiliza el instalador oficial de Android. En Android 8 o posterior el usuario puede tener que autorizar expresamente a NeuroCenter como fuente de instalación en Ajustes. El usuario confirma la instalación y Android conserva el aislamiento y la firma del paquete.

El permiso `REQUEST_INSTALL_PACKAGES` está sujeto a las políticas de distribución de la tienda utilizada. Para una distribución mediante Google Play hay que comprobar que el caso de uso cumple la política vigente; no debe solicitarse sin una necesidad legítima de instalación gestionada por el usuario.

## Pruebas necesarias antes de activar el gestor en la interfaz

1. Instalar dos APK de prueba con permisos Android diferentes y verificar en Ajustes que aparecen como paquetes separados.
2. Comprobar que una app no puede leer el directorio privado ni las credenciales de otra.
3. Conceder Bluetooth a una app y comprobar que la otra sigue sin tenerlo.
4. Revocar un permiso desde Ajustes de Android y comprobar que la app respeta el estado revocado.
5. Verificar descubrimiento, lanzamiento, actualización y desinstalación con el instalador oficial.
6. Probar un paquete que no declare el intent-filter y comprobar que no aparece en NeuroCenter.
7. Verificar que el servicio IPC rechaza UID, paquete o capacidades no autorizadas antes de dar acceso a Agenda o a otros servicios del centro.


## API Binder para servicios del centro

La biblioteca `neurocenter-api` publica `INeuroCenterBridge` (AIDL) y `NeuroCenterCapabilities`. El servicio del host es un componente explícito:

- Paquete: `com.neurocenter`
- Clase: `com.neurocenter.apps.agenda.NeuroCenterBridgeService`
- Acción de binding: `com.neurocenter.action.BIND_BRIDGE`

Ejemplo de binding en una aplicación externa:

```java
Intent intent = NeuroCenterAppContract.bridgeIntent();

ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        INeuroCenterBridge bridge = INeuroCenterBridge.Stub.asInterface(binder);
        // Consultar primero la capacidad y gestionar SecurityException/RemoteException.
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Descartar el proxy; Android puede volver a conectar el servicio.
    }
};
bindService(intent, connection, Context.BIND_AUTO_CREATE);
```

Capacidades disponibles:

| ID | Operaciones protegidas |
|---|---|
| `agenda.read` | Obtener una instantánea JSON de las entradas de la Agenda |
| `agenda.write` | Crear tareas, cambiar su estado y eliminar entradas |
| `credentials.access` | Guardar, recuperar y eliminar secretos cifrados con Android Keystore |

El gestor de permisos de NeuroCenter guarda concesiones en `capabilities.db`, vinculadas al nombre de paquete Android y a la huella SHA-256 de sus certificados de firma actuales. El servicio no acepta identidad del cliente por parámetros: resuelve el UID de Binder con PackageManager, rechaza UIDs ambiguos (por ejemplo, paquetes con `sharedUserId`) y comprueba que el paquete instalado declara el contrato. Cada método protegido vuelve a comprobar la capacidad antes de acceder al recurso. Las concesiones se borran al desinstalar el paquete; las concesiones de una base de datos antigua que no tenga huella de firma se descartan al migrar, en vez de heredarse sin verificación.

Las aplicaciones externas deben incluir `neurocenter-api` como dependencia Android para compilar el contrato AIDL. Para acceder a Android Bluetooth, cámara, notificaciones, red o documentos, deben seguir declarando y solicitando esos permisos/URI por sí mismas; las capacidades Binder no sustituyen permisos del sistema.


## Ejemplo mínimo de manifiesto de una app externa

```xml
<application>
    <activity
        android:name=".NeuroCenterEntryActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="com.neurocenter.action.OPEN_APP" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
        <meta-data
            android:name="com.neurocenter.app.id"
            android:value="mi-aplicacion" />
    </activity>
</application>
```

No copies al manifiesto de la app externa los permisos Bluetooth/cámara/etc. del host. Declara solo los que use esa app. Para sus datos privados, utiliza su almacenamiento interno; para ficheros elegidos por el usuario, usa Storage Access Framework.
