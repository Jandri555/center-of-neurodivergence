# OpenJDK 21 ARM64 incluido en Android

La compilación Android descarga el JRE 21 para AArch64 del proyecto
[AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build),
paquete `jre21-android-arm64.tar.xz`, release `download_jre21` (publicado el 4 de abril de 2026).

- SHA-256: `8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe`
- Fuente: https://github.com/AngelAuraMC/angelauramc-openjdk-build
- Procedencia de la compilación publicada por el proyecto: https://github.com/AngelAuraMC/angelauramc-openjdk-build/actions/runs/22620684091
- OpenJDK se distribuye bajo GPLv2 con Classpath Exception; conservar los
  avisos, licencias y demás materiales del runtime original al redistribuir.

La preparación `android/scripts/prepare_java21_runtime.py` verifica el hash y
coloca las bibliotecas ELF en `jniLibs/arm64-v8a`. La imagen
`lib/modules` se distribuye como archivo de datos `libnm.so` en ese
directorio para que Android la instale junto a las bibliotecas (no es ELF ni se
carga como código). Los restantes recursos siguen en `assets/neurojre21`.
El APK incorpora todos los archivos, sin descargar el JRE en el móvil.

**Cambio de binario experimental:** la preparación sustituye dos cadenas de
rutas en el `libjvm.so` de OpenJDK 21, con la misma longitud total,
para que HotSpot busque la JIMAGE en `nativeLibraryDir/libnm.so`.
No se modifican sus instrucciones de máquina. Se comprueba el patrón exacto
en cada compilación y se conservan los avisos de OpenJDK; este es un
`libjvm.so` **modificado**, no el binario original sin cambios.
Se requiere validar condiciones de redistribución y ofrecer el script de
modificación y la procedencia del código fuente.

**Importante:** la compilación y el empaquetado no son una prueba de arranque
en hardware. `Java21ProbeService` ofrece una sonda nativa en un proceso
separado, sin aceptar clases de aplicaciones externas. La ejecución de `.ncapp` en el UID aislado sigue siendo experimental
hasta que se valide el arranque en un dispositivo real.
