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
coloca las bibliotecas ELF en `jniLibs/arm64-v8a`; la imagen de módulos y
recursos no ejecutables se incluye en `assets/neurojre21`. El APK debe contener
estos archivos, sin descarga del JRE en el dispositivo.

**Importante:** la compilación y el empaquetado no son una prueba de arranque
en hardware. `Java21ProbeService` ofrece una sonda nativa en un proceso
separado, sin aceptar clases de aplicaciones externas. El motor seguro para
ejecutar código `.ncapp` en un UID aislado todavía no está terminado.
