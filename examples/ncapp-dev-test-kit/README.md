# NeuroCenter Dev Test Kit (.ncapp)

Aplicación **Android, Java 21** (versión **1.1.0**) para diagnosticar el runtime y el sandbox de las `.ncapp`.
Es independiente de `examples/ncapp-demo`; **no es un APK** y no necesita APIs Android.

## Qué comprueba

- Entorno Java, versión de VM, arquitectura, límite del heap y presencia de SecurityManager.
- Carga de clases (incluye un `.class` independiente y un `record` de Java), recursos del propio `.ncapp` y `ClassLoader`.
- Creación de hilos, asignación de 256 KiB de memoria y cálculo de rendimiento breve.
- Indicadores de aislamiento (`/proc/self/status`) e intento de listar **solo** el directorio privado estándar del anfitrión; nunca lee ni imprime archivos.
- Test de conexión **solo a `127.0.0.1:9`**, manual, sin envío de datos. Un rechazo TCP no demuestra ausencia de permisos de red.
- API de Agenda: `agenda.read` sin mostrar tareas privadas y `agenda.write` con **confirmación explícita**. La escritura crea una tarea real y exige borrarla manualmente. Ninguna de las dos se ejecuta en «Pruebas seguras».
- Informe local con resultados `OK`, `AVISO`, `ERROR` o `ALERTA`. El resultado no se transmite a ningún servidor.

**Importante:** Las pruebas son orientativas, no una auditoría de seguridad. En especial, un error por ruta inexistente no demuestra bloqueo SELinux. No sustituye la verificación de UID, Binder ni los ensayos de permisos en Android.

## Uso

1. En **GitHub Actions → NeuroCenter Dev Test Kit (.ncapp)**, descarga el artefacto `neurocenter-dev-test-kit-ncapp` de un run exitoso.
2. Descomprime el ZIP de GitHub Actions y selecciona `neurocenter-dev-test-kit.ncapp` en el gestor de aplicaciones de NeuroCenter Android.
3. Ejecuta «Pruebas guiadas (paso a paso)». Pulsa **Ejecutar** para cada prueba, o **Saltar esta prueba** para continuar sin ejecutarla.
   Nunca se lanzan todas en una única acción: si la JVM aborta, sabremos cuál falló.
4. Si se produce un aborto, copia el informe de NeuroCenter Android: la última línea `DEVKIT_1.1.0_BEGIN:<prueba>` sin un `END` correspondiente identifica dónde se interrumpió el proceso (no demuestra que esa prueba causara el bug).
5. Consulta el «Informe» para los resultados obtenidos en esa sesión.
6. Para probar `agenda.read`, usa el botón «Permisos de Agenda»; repite con el permiso denegado y concedido en el gestor de NeuroCenter.
7. Para probar `agenda.write`, pulsa «Probar agenda.write» y confirma. Después elimina la tarea `[Dev Test Kit] Tarea de prueba` desde la Agenda.

Estas modificaciones **no arreglan por sí mismas** un fallo de punteros nativos. Ayudan a aislarlo sin desactivar el etiquetado de punteros del heap.

El kit es **solo móvil** (`NeuroCenter-App-Desktop: false`) para evitar declarar funcionalidades de escritorio que aún no tiene.

## Compilación local

Requiere JDK 21. Desde la raíz del repositorio:

```sh
mkdir -p build/devtest/classes dist
javac --release 21 -encoding UTF-8 -d build/devtest/classes \
  examples/ncapp-dev-test-kit/src/main/java/com/neurocenter/devkit/*.java
jar --create --file dist/neurocenter-dev-test-kit.ncapp \
  --manifest examples/ncapp-dev-test-kit/MANIFEST.MF \
  -C build/devtest/classes . \
  -C examples/ncapp-dev-test-kit/src/main/resources .
```

El workflow dedicado compila, ejecuta pruebas del contrato JSON y sube el `.ncapp` como artefacto independiente. Para cambiar el formato de UI o introducir más capacidades deberá actualizarse el anfitrión y el kit de forma coordinada.
