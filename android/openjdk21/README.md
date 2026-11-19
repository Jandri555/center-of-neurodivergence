# OpenJDK 21 Android — parche de rutas para NeuroCenter

## Incidencia confirmada por el móvil

NeuroCenter Debug 1.6.9 registra, desde el UID Android aislado:

```text
JVM stage 3: dlopen completado
JVM stage 4: entrando en JNI_CreateJavaVM
Error occurred during initialization of VM
Failed setting boot class path.
```

No es un error de `.ncapp`; HotSpot no encuentra su imagen modular.

## Causa y procedencia exacta

El binario usado en los APK procede de
[AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build),
workflow [22620684091](https://github.com/AngelAuraMC/angelauramc-openjdk-build/actions/runs/22620684091),
rama `buildjre17-21`, commit
`196da32d80fe96d6967ca1c154f102cff0dfc389`.
La compilación original clona OpenJDK `jdk21u` tag `jdk21.0.1` y aplica
`patches/jre_21/android/jdk21u_android.diff`.

Ese parche del proveedor **elimina** la lógica de `JAVA_HOME` de la
función `os::jvm_path` bajo `__ANDROID__` y utiliza la ruta real de
`libjvm.so`. Por eso **no funcionaba** el intento previo de activar
`-Dsun.java.launcher.is_altjvm=true`: el binario ni siquiera incluye el
literal `"/jre/lib"` esperado. El empaquetado actual de NeuroCenter coloca
las bibliotecas bajo `<nativeLibraryDir>/libjvm.so`, no en
`<JAVA_HOME>/lib/server/libjvm.so`. El cálculo de Java Home, heredado de
Linux, sube directorios inapropiados.

## Arreglo de origen (no parchear ELF a ciegas)

`patches/90_neurocenter_native_jimage_home.diff` modifica
`src/hotspot/os/linux/os_linux.cpp` **antes de compilar OpenJDK**.
Si `NEUROCENTER_JRE_HOME` está definido por el lanzador, HotSpot toma ese
directorio tanto para `java.home` como para buscar sus bibliotecas. El host
debe fijar esa variable **dentro del UID aislado**, con la ruta nativa del
APK. En ausencia de la variable, conserva el comportamiento del proveedor.

La reubicación de módulos `lib/modules → libnm.so` aún necesita las dos
sustituciones de cadenas auditadas del script
`android/scripts/prepare_java21_runtime.py`; esas dos sí aparecían en el
binario original y superaban el CI. El parche fuente resuelve el cálculo de
`java.home` y `dll_dir`, no la reubicación de la JIMAGE.

## Estado actual

- **Versión utilizable para diagnosticar:** Android 1.6.9-debug.
- **Parche fuente:** preparado, pendiente de compilar una distribución ARM64
  verificable y sustituir el `libjvm.so` empaquetado.
- **No hay un APK 1.6.10 funcional**: el intento de corregirlo solo con
  literales del ELF se descartó.
- **No fusionar esta función en dev** sin demostrar ejecución y aislamiento
  en el teléfono.

`build-neurocenter-hotspot.yml` proporciona una ruta manual para construir
la JVM del proveedor con este parche. Su resultado requiere validación y
huella SHA-256 fijada antes de integrarlo en el empaquetado Android.
