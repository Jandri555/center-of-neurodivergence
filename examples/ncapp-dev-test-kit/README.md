# NeuroCenter Dev Test Kit 1.4.0 (.ncapp)

**Un único paquete Java 21 para Windows y Android**, adaptado a las APIs de la rama `dev`. No es un APK ni requiere renombrar archivos JAR.

## Qué contiene

- **Escritorio (`DevTestKitDesktop`)**: prueba manual de Java 21, ClassLoader y recurso empaquetado, los permisos declarados, el diálogo del anfitrión al pedir `network.connect`, `credentials.access`, `agenda.read` y `agenda.write`, y la disponibilidad del plugin Agenda Bridge. Incluye la casilla `UNSAVED_CHANGES` para comprobar que NeuroCenter advierte de cambios sin guardar al cerrar o actualizar la app. No envía peticiones de red ni escribe credenciales.
- **Android (`DevTestKitMobile`)**: prueba el proceso aislado, clases Java 21, recursos, hilos, memoria, rendimiento, UID, Seccomp y CLDR. Los ensayos de red son **manuales**, solo hacia `127.0.0.1:9`. Las operaciones de Agenda también son manuales; la escritura real necesita confirmación explícita y crea una tarea que tendrás que borrar después.

## Permisos del manifiesto (contrato actual)

```text
NeuroCenter-App-Id: com.neurocenter.devtestkit
NeuroCenter-App-Desktop: true
NeuroCenter-App-Mobile: true
NeuroCenter-Mobile-Class: com.neurocenter.devkit.DevTestKitMobile
NeuroCenter-App-Permissions: agenda.read,agenda.write,network.connect,credentials.access
```

El manifiesto es el **límite máximo**. El kit solo pide permisos al usar sus pruebas, nunca al abrirse. NeuroCenter muestra «Permitir», «Denegar», «Permitir siempre» y «Denegar siempre» cuando se solicita un permiso permitido por el manifiesto. Si un permiso no está declarado no puede solicitarse.

**Agenda:** un permiso no crea la API. `agenda.read` y `agenda.write` solo pueden funcionar si el plugin **Agenda Bridge** está instalado, habilitado y ha registrado el servicio. Android puede informar `SIN PLUGIN` en esta prueba; no equivale a un fallo de aislamiento.

## Instalación, actualización y desinstalación

Instala **el mismo archivo** `neurocenter-dev-test-kit.ncapp` en cualquiera de las dos plataformas. Para actualizar, utiliza «Instalar o actualizar» y selecciona la nueva versión con el mismo `NeuroCenter-App-Id`; no hace falta desinstalar la anterior ni reiniciar NeuroCenter. El kit no incluye migraciones de esquemas ni conversiones de manifiestos antiguos. Para comprobar el ciclo de vida de escritorio:

1. Abre el kit y activa `UNSAVED_CHANGES`; intenta cerrarlo o actualizarlo para comprobar la advertencia.
2. Desactiva la casilla, cierra y vuelve a abrir; confirma que no aparece el aviso.
3. Comprueba cada botón de permiso y revoca/concede sus decisiones desde el gestor de NeuroCenter.
4. Prueba la actualización y desinstalación en caliente sin cerrar otras aplicaciones.

En Android, ejecuta «Pruebas guiadas (paso a paso)» para que cada prueba nativa sea independiente. Puedes usar «Probar comunicación JNI» para comprobar el puente básico; «Formato CLDR» aparece en Informe. Las pruebas de Agenda están fuera de la batería segura.

## Compilar

El workflow **NeuroCenter Dev Test Kit (.ncapp)** construye Java 21, verifica el contrato de escritorio y el contrato JSON móvil, comprueba el manifiesto, las clases y el `ServiceLoader`, y publica el paquete `neurocenter-dev-test-kit.ncapp`.

En local, desde la raíz del repositorio, con JDK 21 y Maven:

```sh
mvn -B -DskipTests package
mkdir -p build/devtest/classes dist
javac --release 21 -encoding UTF-8 -cp target/classes -d build/devtest/classes \
  examples/ncapp-dev-test-kit/src/main/java/com/neurocenter/devkit/*.java
jar --create --file dist/neurocenter-dev-test-kit.ncapp \
  --manifest examples/ncapp-dev-test-kit/MANIFEST.MF \
  -C build/devtest/classes . \
  -C examples/ncapp-dev-test-kit META-INF/services \
  -C examples/ncapp-dev-test-kit/src/main/resources .
```

El Dev Test Kit es una herramienta de diagnóstico, **no una prueba exhaustiva de seguridad**: un resultado `AVISO` en `/proc` o un puerto TCP cerrado no demuestra por sí solo aislamiento.
