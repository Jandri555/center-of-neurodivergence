# Formato .ncapp y runtime Android Java 21

## Objetivo acordado
- Un solo archivo `.ncapp` instalable dentro de NeuroCenter (ZIP/JAR con `.class`, no APK).
- La lógica Java 21 se comparte entre escritorio y Android. Las interfaces pueden ser diferentes.
- Android 10 (API 29) como mínimo, con OpenJDK 21 ARM64 empaquetado dentro del APK del host.
- Cada app en una instancia aislada propia, sin heredar permisos Android del host.
- Los accesos a Agenda, credenciales, documentos, red, etc. se autorizan por una API del host con capacidades por app.

## Estado de implementación (NO confundir con el objetivo)
- ✅ Android `minSdk=29`.
- ✅ Escritorio: el cargador y el instalador aceptan archivos `.ncapp` que son JAR.
- ✅ Android: importa `.ncapp` con el selector de documentos, valida el manifiesto, lo instala en almacenamiento privado, permite listar y desinstalar. No instala APK.
- ✅ Android: comprobación manual de un servicio `android:isolatedProcess=true` creado con `bindIsolatedService`, que recibe el archivo por descriptor Binder y devuelve su SHA-256. No ejecuta el archivo.
- ✅ Preferencias de capacidades registradas por ID + SHA-256 del paquete. Modificar un paquete invalida sus permisos anteriores.
- ✅ **OpenJDK 21 ARM64 incluido en la compilación del APK**: se descarga un release fijo de Amethyst, se verifica SHA-256 y se empaquetan módulos + bibliotecas nativas. Un servicio de diagnóstico independiente permite comprobar el arranque real de HotSpot 21; no equivale a haber pasado una prueba en dispositivo.
- ⛔ Ejecutar código `.ncapp` en un proceso aislado sigue pendiente: los procesos `isolatedProcess` no pueden abrir rutas ordinarias del directorio privado de NeuroCenter, y el módulo `lib/modules` necesita acceso por ruta. La sonda no ejecuta aplicaciones de terceros.
- ⛔ Falta IPC de la API de NeuroCenter, ejecución de código, motor de UI móvil, aislamiento de cada ejecución y pruebas de ataques.
- ✅ Windows: el instalador jpackage registra .ncapp y entrega el archivo a la instancia ya iniciada.
- ⛔ Falta registrar la asociación Android y probar en dispositivo real.

## Manifest de una .ncapp
Este archivo es un JAR común con manifest principal:

```text
Manifest-Version: 1.0
NeuroCenter-App-Id: ejemplo.app
NeuroCenter-App-Desktop: true
NeuroCenter-App-Mobile: true
Implementation-Title: Ejemplo
Implementation-Version: 1.0
Implementation-Vendor: Desarrollador
```

En escritorio sigue siendo obligatorio el proveedor `META-INF/services/com.neurocenter.api.NeuroApp`.
Todavía falta definir la interfaz móvil pública que implementarán las apps.

## Aislamiento y runtime: condiciones para aceptar el cambio final
1. Empaquetar la distribución Android ARM64 OpenJDK 21 de Amethyst, verificar su SHA-256 y mantener avisos de licencia. Hecho mediante `android/scripts/prepare_java21_runtime.py` y el build Gradle; quedan pruebas reales de compatibilidad.
2. Lanzar la JVM en el proceso Android **aislado**, nunca en el proceso principal privilegiado.
3. Confirmar en un dispositivo que `Process.isIsolated() == true`, que una app no tiene permiso de red, almacenamiento privado del host ni código nativo compartido.
4. Entregar exclusivamente descriptores de archivo y datos expresamente autorizados; jamás un directorio privado completo ni una interfaz de host sin controles.
5. Vincular cada llamada a su instancia y huella SHA-256 de paquete en el host; comprobar el permiso *en cada operación*, sin confiar en IDs recibidos del cliente.
6. Poner una política de ejecución que impida a Java 21 cambiar el Security Manager, invocar JNI arbitrario o ampliar su CodeSource. Considerar la JVM hostil; la barrera efectiva es el UID/SELinux aislado.
7. Pruebas de permisos denegados, API de agenda, almacenamiento, red, Bluetooth y archivo corrupto; pruebas de actualización/revocación.
8. Lanzar la UI de forma declarativa a través del host; no permitir referencias directas a Views del proceso host.

**Estado de seguridad:** el proceso aislado verificado por Binder es un primer paso. Guardar una preferencia de permiso NO equivale a hacerla cumplir. Actualmente no se ejecuta código `.ncapp` en Android; se mantiene así intencionadamente hasta completar las barreras.

Fuentes técnicas:
- https://developer.android.com/reference/android/content/Context#bindIsolatedService(android.content.Intent,int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection)
- https://developer.android.com/about/versions/10/behavior-changes-10
- https://github.com/PojavLauncherTeam/PojavLauncher
- https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html
