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
- 🧪 **Android 1.6.6 experimental:** el host pasa solo el descriptor del JAR. Android extrae `libnm.so` (imagen JIMAGE de módulos) y las bibliotecas ELF desde el APK firmado a `nativeLibraryDir`, que debería ser accesible al UID aislado. La compilación modifica exactamente dos cadenas de rutas del binario HotSpot y verifica su presencia. No se extrae el JRE a `files/jre21`. Falta comprobar en el móvil que el instalador acepta el archivo JIMAGE etiquetado `.so` y que la JVM arranca.
- ✅ IPC de arranque y motor de interfaz móvil JSON (`title`, `text`, `actions`) implementados; se utiliza una instancia aislada distinta para cada ejecución.
- ✅ Puente móvil inicial con comprobación de permisos y SHA-256 para `agenda.read/list` y `agenda.write/addTask` mediante solicitudes JSON; el proceso aislado no accede a los datos directamente.
- ⛔ Faltan puentes para credenciales, archivos, red y demás capacidades, además de pruebas de ataques.
- ✅ Windows: el instalador jpackage registra .ncapp y entrega el archivo a la instancia ya iniciada.
- ✅ Android registra intents VIEW/SEND para importar `.ncapp`. Falta probar el arranque nuevo en un dispositivo físico.

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
Para Android, la manifest debe incluir `NeuroCenter-Mobile-Class` con el
nombre de la clase que implementa `renderJson()` y `onAction(String)`.
Ver `docs/ncapp-mobile-api.md`.

## Aislamiento y runtime: condiciones para aceptar el cambio final
1. Empaquetar la distribución Android ARM64 OpenJDK 21 de Amethyst, verificar su SHA-256 y mantener avisos de licencia. Hecho mediante `android/scripts/prepare_java21_runtime.py` y el build Gradle; quedan pruebas reales de compatibilidad.
2. Lanzar la JVM en el proceso Android **aislado**, nunca en el proceso principal privilegiado.
3. Confirmar en un dispositivo que `Process.isIsolated() == true`, que una app no tiene permiso de red, almacenamiento privado del host ni código nativo compartido.
4. Entregar únicamente el descriptor de la .ncapp y datos autorizados; los módulos Java proceden del directorio de bibliotecas del APK firmado. No compartir directorios privados ni APIs de host sin comprobación.
5. Vincular cada llamada a su instancia y huella SHA-256 de paquete en el host; comprobar el permiso *en cada operación*, sin confiar en IDs recibidos del cliente.
6. Poner una política de ejecución que impida a Java 21 cambiar el Security Manager, invocar JNI arbitrario o ampliar su CodeSource. Considerar la JVM hostil; la barrera efectiva es el UID/SELinux aislado.
7. Pruebas de permisos denegados, API de agenda, almacenamiento, red, Bluetooth y archivo corrupto; pruebas de actualización/revocación.
8. Lanzar la UI de forma declarativa a través del host; no permitir referencias directas a Views del proceso host.

**Estado de seguridad:** el lanzamiento aislado Java 21 está implementado a nivel de código, pero no validado en un móvil físico. Las operaciones de Agenda se comprueban en el host en cada solicitud; guardar otras concesiones de permisos todavía no habilita sus servicios. Nunca ejecutar el JAR en el proceso principal como alternativa.

Fuentes técnicas:
- https://developer.android.com/reference/android/content/Context#bindIsolatedService(android.content.Intent,int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection)
- https://developer.android.com/about/versions/10/behavior-changes-10
- https://github.com/PojavLauncherTeam/PojavLauncher
- https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html

## Experimento de empaquetado Android 1.6.6

La versión anterior instalaba la JIMAGE en `files/jre21/lib/modules` y trataba de entregar un FD del directorio al UID aislado. SELinux bloquea el directorio `app_data_file`. La 1.6.6 deja de hacerlo:

1. El build verificado por SHA-256 separa los ELF y reubica `lib/modules` como `jniLibs/arm64-v8a/libnm.so` (datos **no ELF**, no se ejecutan).
2. Gradle empaqueta ese recurso sin pasarle `strip`, y `useLegacyPackaging=true` solicita su extracción al instalar el APK.
3. Dos cadenas de formato del HotSpot binario ARM64 se sustituyen sin alterar el tamaño del ELF: `%/lib/modules` por `%/libnm.so`, y `%s%slib%smodules` por `%s%slibnm.so`. Se aborta el build si el proveedor modifica el binario y deja de coincidir exactamente.
4. `java.home` se fija al `nativeLibraryDir` real; el host ya no comparte un FD de carpeta. Se siguen comprobando el SHA-256 de la .ncapp y el aislamiento del proceso.

**Este cambio es experimental, no una garantía de funcionalidad o seguridad.** Debemos validar en hardware la instalación, el acceso a `libnm.so`, el arranque JNI de HotSpot y la carga de clases. Las clases Java pueden intentar abrir rutas estándar como `java.home/conf/security/java.security`; al no existir esa jerarquía todavía pueden producirse errores. No se hará fallback a una JVM con permisos del host.

## Variantes Android DEBUG y RELEASE (1.6.9+)

La compilación genera **dos APK con el mismo applicationId `com.neurocenter`**. Se instalan uno encima del otro, no simultáneamente. La variante DEBUG añade `-debug` a `versionName` y al nombre visible del lanzador.

- **DEBUG:** el anfitrión crea un pipe unilateral antes de `JNI_CreateJavaVM`. El extremo de escritura se pasa por Binder al `isolatedProcess`, nunca acceso a directorios privados. El puente nativo Debug envía mensajes por ese FD y redirige stdout/stderr **solo dentro del proceso aislado**. El anfitrión drena el pipe en un hilo y conserva hasta 64 KiB recientes en RAM. Después de un fallo permite copiar y **Compartir informe** (texto plano, sin ADB). Se añade el historial de salida de Android cuando está disponible (API 30+). No se guarda ni envía automáticamente y puede contener datos privados.
- **RELEASE:** usa la implementación Java sin captura del `src/release`; el puente JNI no incluye el código DEBUG gracias a la definición CMake condicionada a `Debug`. No muestra opciones de exportar ni usa pipes de diagnóstico. No se modifica el aislamiento ni la política de permisos de las `.ncapp`.

El PIPE no garantiza capturar todo: un fallo anterior a su instalación, mensajes enviados exclusivamente a logcat o un cierre brusco sin emitir texto pueden dejarlo vacío. La propia ejecución de HotSpot en Android **continúa siendo experimental**.

**Firma:** para poder actualizar una instalación de prueba existente, ambos APK generados por CI están firmados temporalmente con la misma clave de prueba `stableDebug`. El llamado RELEASE es por ahora una **build de prueba sin herramientas de diagnóstico, NO una release publicable**. Antes de distribuir NeuroCenter, sustituir por una clave privada de producción protegida y configurar un proceso de firma de releases.

**Construcción local:**
```sh
gradle -p android :app:assembleDebug :app:assembleRelease
```

Artefactos esperados: `android/app/build/outputs/apk/debug/app-debug.apk` y `android/app/build/outputs/apk/release/app-release.apk`.
