# Center of Neurodivergence

La versión de la aplicación se define en un único lugar: la etiqueta `<version>` de `pom.xml`. Maven la incorpora automáticamente al manifiesto del JAR.

Panel de escritorio en Java (Swing + FlatLaf) con varias apps integradas y extensibles.

| App | Qué hace |
|---|---|
| **Agenda** | Calendario, tareas, eventos y avisos mientras la aplicación está abierta. |
| **Bionic PDF** | Vista Original y vista Lectura con Bionic Reading. Sus ajustes de lectura (fuente, tamaño, interlineado y fondo) son independientes del tema general. |

## Requisitos

- JDK 17 o superior
- Maven 3.9 o superior

## Compilar y ejecutar

```bash
mvn package
java -jar target/center-of-neurodivergence.jar
```


## Generar el instalador de Windows (.exe)

En Windows, el perfil Maven `windows-exe` se activa automáticamente al ejecutar:

```powershell
mvn package
```

Cada compilación genera el instalador en una carpeta propia con versión y fecha/hora, por ejemplo:

```text
target/installer/1.0-20261117-102239/Center of Neurodivergence-1.0.exe
```

Así no es necesario sobrescribir ni borrar el instalador de la compilación anterior. Para localizar los instaladores generados:

```powershell
Get-ChildItem target\\installer -Recurse -Filter "*.exe"
```

Para crear instaladores `.exe`, `jpackage` necesita WiX Toolset 3.0 o superior. En el equipo de desarrollo puedes instalar la versión 3.14.1 desde PowerShell con Chocolatey:

```powershell
choco install wixtoolset --version=3.14.1.20250415 --no-progress -y
```

Maven añade automáticamente la carpeta habitual de WiX 3.14 al `PATH` del proceso `jpackage`, sin modificar el `PATH` global de Windows. Si WiX está instalado en otra ubicación, indica su carpeta `bin` al compilar:

```powershell
mvn package "-Dwix.bin=D:/Ruta/a/WiX/bin"
```

La ruta predeterminada es `C:/Program Files (x86)/WiX Toolset v3.14/bin`.

El instalador incluye un runtime de Java y crea accesos directos en el menú Inicio. La compilación de GitHub Actions instala WiX automáticamente y publica el `.exe` como artefacto de la ejecución.


**Compatibilidad con Temurin 25:** no se debe pasar manualmente `--module-path` a `jpackage` apuntando a `%JAVA_HOME%\\jmods`. Temurin 25 habilita JEP 493 y puede crear el runtime sin JMOD; forzar una carpeta `jmods` antigua puede mezclar módulos de distintas versiones de Java. Maven deja que `jpackage`/ `jlink` usen el runtime del mismo JDK que ejecuta la compilación.

## Datos de usuario

Los datos se guardan en la carpeta `CenterOfNeurodivergence`:

- Windows: `%APPDATA%\\CenterOfNeurodivergence\\`
- macOS: `~/Library/Application Support/CenterOfNeurodivergence/`
- Linux: `~/.local/share/CenterOfNeurodivergence/`

La carpeta `apps/` contiene las apps externas instaladas, `themes/` los temas `.tme` instalados y `data/` los datos de cada app.

El registro de aplicaciones externas se guarda en `apps.db` (SQLite), dentro de esa misma carpeta. La tabla `installed_apps` conserva el identificador, nombre, autor, versión, nombre del JAR, fecha de instalación, tamaño en bytes y la opción `autorun` (0 = desactivado, 1 = activado). La base de datos es la fuente de verdad: al iniciar solo se cargan los JAR registrados.

En **Ajustes → Gestionar apps → Configurar inicio automático** puedes activar o desactivar el arranque automático de cada app. Las apps con `autorun = 1` se inician al abrir Center of Neurodivergence, pero la pantalla inicial sigue siendo Inicio.

## Gestionar apps externas

Abre **Ajustes** (⚙) en el panel y entra en **Gestionar apps**. Desde ahí puedes instalar un archivo JAR o programar su desinstalación. Los cambios se aplican al reiniciar, porque una app cargada puede mantener su JAR bloqueado en Windows.

La API de extensiones de escritorio es `NeuroApp` con su contexto `AppContext`. Los JAR deben registrar la implementación de escritorio mediante el servicio:

`META-INF/services/com.neurocenter.api.NeuroApp`

El manifiesto JAR declara las plataformas compatibles mediante dos flags booleanos:

```text
NeuroCenter-App-Desktop: true
NeuroCenter-App-Mobile: false
```

- Solo escritorio: `true / false`.
- Solo móvil: `false / true`.
- Escritorio y móvil: `true / true`.

Cada flag ausente equivale a `false`. Si ambos faltan o ambos valen `false`, el paquete no es compatible con ninguna plataforma y se rechaza. No existe compatibilidad implícita para paquetes antiguos: el tema negro anterior debe añadir expresamente sus flags. Si se intenta instalar o cargar en escritorio una app solo móvil, aparece un error indicando que el dispositivo no es compatible.

**Código por plataforma:** una app multiplataforma mantiene dos implementaciones con el mismo identificador:
- Escritorio: implementa `NeuroApp`, recibe `AppContext` y construye su interfaz Swing. Los flags van en el `MANIFEST.MF` del JAR.
- Android: implementa `NeuroMobileApp`, recibe `NeuroMobileContext` y construye su interfaz nativa mediante una `View`. Los flags van como `<meta-data>` en el `AndroidManifest.xml` del APK, con los nombres `NeuroCenter-App-Desktop` y `NeuroCenter-App-Mobile` y valores `true` o `false`.

Ambos contextos heredan de `NeuroAppContext`, que ofrece los servicios neutrales (plataforma, carpeta de datos y ajustes). `context.platform()` devuelve `NeuroPlatform.DESKTOP` en escritorio y `NeuroPlatform.MOBILE` en Android. Las clases de lógica compartida también pueden consultar directamente `NeuroPlatform.current()`, que detecta el runtime sin depender de Swing. La lógica de negocio compartida puede aceptar `NeuroAppContext`; las clases de presentación son distintas y no deben importar Swing en la implementación Android.

La API móvil se publica en el módulo Gradle `:neurocenter-api` del proyecto `android/`. Una app Android puede añadir `implementation(project(":neurocenter-api"))` cuando forme parte del mismo proyecto Gradle, o consumir el AAR equivalente al distribuir la API.

**Estado del host móvil:** el proyecto Android ya incluye la API móvil como biblioteca reutilizable, pero aún no incorpora el gestor/cargador de paquetes externos Android. Por tanto, la detección de plataforma y los contratos de las dos implementaciones están definidos, pero todavía no se pueden instalar y ejecutar APK de apps externas desde el host Android.

**Seguridad:** una app externa es código ejecutado con los permisos de tu usuario. No existe aislamiento de seguridad entre una app externa y la aplicación principal. Instala únicamente paquetes de fuentes de confianza.

## Sincronización de Agenda con Android (en desarrollo)

La Agenda ya puede preparar un historial persistente de operaciones al activar **Preparar sincronización**. Registra altas, modificaciones, cambios de estado y borrados en `agenda-operations.jsonl`, manteniendo `agenda.json` como copia local. Las entradas que ya existían se incorporan mediante una línea base creada una sola vez en cada dispositivo.

El historial utiliza identificadores idempotentes y relojes causales. Los borrados se conservan como operaciones explícitas para evitar que una entrada antigua reaparezca. Los avisos ya entregados permanecen locales a cada dispositivo.

Hay una primera app Android nativa que comparte el motor de datos y el protocolo de sincronización con el escritorio. El móvil puede actuar como nodo central de varios PCs: conserva un reloj de confirmación independiente por ordenador, de modo que sincronizar un cambio con el PC de clase no lo da por recibido en el PC de casa. Los equipos nuevos reciben el historial completo, incluidos los borrados antiguos. Para usar este modo, actualiza todos los PCs y Android a una versión compatible con el protocolo 2. Su identificador de paquete es `com.neurocenter` y su almacenamiento privado replica la estructura común de escritorio: `CenterOfNeurodivergence/{apps,themes,data}`. La Agenda guarda sus archivos en `data/agenda/`; las carpetas para apps y temas ya se preparan para el futuro centro de aplicaciones. El cambio de paquete no migra datos de la antigua app `com.neurocenter.agenda`. Las dos vías de transporte tienen ya una implementación de código:
- **Bluetooth clásico RFCOMM/SPP:** Android espera la conexión; en Windows hay que emparejar el teléfono y configurar un puerto COM Bluetooth saliente. Después, en Agenda, pulsa **Sincronizar Bluetooth** y selecciona ese puerto.
- **Cable USB mediante Android Open Accessory (AOA):** el PC intenta iniciar el modo accesorio y Android abre el canal USB. En Windows puede hacer falta asociar la interfaz AOA a un controlador compatible con libusb (WinUSB o libusbK).

**Estado real:** las compilaciones de escritorio y Android han pasado en CI en revisiones recientes, pero todavía no he probado la conexión con un móvil y un adaptador físicos. La disponibilidad de SPP y AOA depende del teléfono, el controlador y el cable. El USB no es necesariamente plug-and-play.

**Limitaciones actuales:** la app Android todavía no tiene paridad completa con los avisos de eventos del escritorio. El historial se conserva después de sincronizar y aún no se compacta; no borres `agenda-operations.jsonl` ni `agenda-sync.json` mientras la sincronización esté preparada.

El diseño y las fases están documentados en [docs/agenda-sync.md](docs/agenda-sync.md).

## Temas externos (.tme)

Desde **Ajustes → Gestionar temas** puedes instalar o desinstalar temas. Un archivo `.tme` es un ZIP renombrado: contiene un `theme.json` en la raíz y, opcionalmente, una carpeta `resources/`. No contiene ni ejecuta código Java, JavaScript o CSS. Los estilos se declaran como colores en JSON.

Ejemplo de estructura:

```text
MiTema.tme
├── theme.json
└── resources/
    └── icons/
        ├── agenda.png
        └── pdf-reader.png
```

Ejemplo de `theme.json`:

```json
{
  "id": "mi-tema",
  "name": "Mi tema",
  "author": "Tu nombre",
  "version": "1.0",
  "compatibility": {
    "desktop": true,
    "mobile": false
  },
  "base": "dark",
  "background": {
    "image": "resources/background.png",
    "darkening": 30,
    "transparency": 10
  },
  "centerIcon": "resources/center-icon.png",
  "colors": {
    "background": "#171923",
    "surface": "#222635",
    "foreground": "#F2F4F8",
    "text": "#F2F4F8",
    "primary": "#8B9CFF",
    "selection": "#46558F",
    "selectionText": "#FFFFFF",
    "border": "#41485C",
    "button": "#30384D",
    "buttonText": "#FFFFFF",
    "input": "#202536",
    "inputText": "#F2F4F8"
  },
  "appIcons": {
    "agenda": "resources/icons/agenda.png",
    "pdf-reader": "resources/icons/pdf-reader.png"
  }
}
```

`compatibility.desktop` y `compatibility.mobile` indican en qué plataformas se puede aplicar el tema. Cada flag ausente equivale a `false`; ambos ausentes o ambos a `false` hacen que el tema no sea compatible con ningún dispositivo. Los temas antiguos también deben actualizar su manifiesto. Si se intenta instalar o aplicar en escritorio un tema solo móvil, se muestra un error de dispositivo no compatible. El host Android todavía no incluye el gestor de temas externos.

`base` debe ser `light` u `dark` y determina el aspecto base de FlatLaf; `colors` sobrescribe los colores indicados.

- `background.image`: PNG de fondo. Se escala para cubrir el área de la ventana sin deformarse.
- `background.darkening`: oscurecimiento de 0 a 100. El valor 0 no oscurece y 100 produce negro.
- `background.transparency`: transparencia de la imagen de 0 a 100. El valor 0 es opaco y 100 hace invisible la imagen.
- `centerIcon`: PNG para el icono de NeuroCenter. Se aplica a la ventana y a la bandeja del sistema. En Windows también actualiza los accesos directos existentes del Escritorio, menú Inicio y carpeta de accesos anclados a la barra de tareas, siempre que apunten al ejecutable de NeuroCenter. Si se omite, se restaura el icono integrado en el EXE.
- `appIcons`: iconos opcionales de apps, asociados mediante sus identificadores internos.

Todas las rutas deben permanecer dentro de `resources/`. El fondo y el icono de NeuroCenter deben ser PNG; los iconos de apps admiten formatos de imagen que Java pueda decodificar, aunque se recomienda PNG. Los recursos se validan al instalar el paquete. La app Android todavía no aplica temas externos: el flag móvil queda definido en el formato para el futuro gestor de temas Android.

Por seguridad, el instalador rechaza rutas ZIP inseguras, archivos fuera de `theme.json` y `resources/`, más de 200 entradas o más de 20 MB descomprimidos.

## Crear una app externa

Mira `example-app/`. Implementa `com.neurocenter.api.NeuroApp` y registra la clase mediante `META-INF/services/com.neurocenter.api.NeuroApp`.

La implementación de escritorio recibe un `AppContext` con acceso a su carpeta de datos, ajustes, barra de estado, ventana principal y navegación mediante `openApp(id)`. Consulta `context.platform()` para conocer la plataforma desde el código común. Para Android, crea una implementación nativa separada de `NeuroMobileApp` que reciba `NeuroMobileContext` y devuelva una `View`. Ambas implementaciones deben usar el mismo identificador estable y pueden delegar la lógica compartida a clases que dependan solo de `NeuroAppContext`.

Compila la versión de escritorio contra la aplicación como dependencia `provided`; no empaquetes las librerías de la aplicación dentro del JAR. La API móvil está disponible en el módulo Android, aunque el cargador de extensiones Android todavía está pendiente.

Para que el registro pueda mostrar metadatos fiables, añade al manifiesto del JAR estas entradas:
- `NeuroCenter-App-Id`: identificador estable de la app.
- `Implementation-Title`: nombre visible.
- `Implementation-Vendor`: autor o entidad responsable.
- `Implementation-Version`: versión de la app.

Si falta alguna entrada, el instalador utiliza valores de reserva (por ejemplo, el nombre del JAR y «Desconocido» para el autor).

## Estructura del código

```text
com.neurocenter
├── api/       NeuroApp, AppContext
├── core/      MainWindow, AppLoader, AppPaths, PropertiesStore, ThemeManager
├── ui/        RoundedPanel, BadgeIcon
└── apps/      Agenda y Bionic PDF
```

## Limitaciones conocidas

- Los avisos de la agenda solo funcionan con la aplicación abierta.
- Los PDF escaneados solo se pueden ver en la vista Original.
- No se admiten PDF protegidos con contraseña.
- Los cambios de instalación y desinstalación requieren reiniciar la aplicación.
- El reflujo de texto del PDF usa heurísticas y puede alterar el orden en documentos complejos.
