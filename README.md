# Center of Neurodivergence

La versión de la aplicación se define en un único lugar: la etiqueta `<version>` de `pom.xml`. Maven la incorpora automáticamente al manifiesto del JAR.

Panel de escritorio en Java (Swing + FlatLaf) con varias apps integradas y extensibles.

| App | Qué hace |
|---|---|
| **Agenda** | Calendario, tareas, eventos y avisos mientras la aplicación está abierta. |
| **Bionic PDF** | Vista Original y vista Lectura con Bionic Reading. Sus ajustes de lectura (fuente, tamaño, interlineado y fondo) son independientes del tema general. |

## Requisitos

- JDK 21 (Temurin recomendado)
- Maven 3.9 o superior
- Maven 3.9 o superior

## Compilar y ejecutar

```bash
mvn package
java -Djava.security.manager=allow -jar target/center-of-neurodivergence.jar
```


## Instalación y actualización en Windows

El instalador EXE de Windows incluye un **asistente en español** con una
presentación de Center of Neurodivergence, una explicación del tipo de
instalación, un selector de carpeta, una opción de acceso directo al escritorio,
un resumen antes de empezar y un botón **Instalar** o **Actualizar**.

- **Carpeta predeterminada:** `%LOCALAPPDATA%\Center of Neurodivergence`,
  modificable en el asistente. No necesita permisos de administrador.
- **Modo de instalación:** instala la app y un runtime privado **Java 21**.
  No modifica el Java global de Windows.
- **Actualización:** reutiliza la instalación anterior si ya se utilizó este
  asistente. También detecta y retira el instalador anterior basado en
  `jpackage`/Windows Installer antes de instalar la nueva versión; no es
  necesario desinstalar NeuroCenter manualmente.
- **Datos del usuario:** se mantienen en
  `%APPDATA%\CenterOfNeurodivergence`. Actualizar no borra apps `.ncapp`,
  plugins, temas, Agenda ni preferencias de ese directorio.
- **Integración:** crea un acceso directo del menú Inicio, ofrece un acceso
  directo de escritorio y registra el formato `.ncapp`.

**Antes de actualizar**, cierra NeuroCenter por completo desde el icono de
bandeja para que Windows pueda sustituir el lanzador y el runtime Java.

### Compilar el instalador

En Windows necesitas JDK 21, Maven y **Inno Setup 6**:

```powershell
choco install innosetup -y
mvn clean package
```

`mvn package` también funciona. El perfil Windows se activa automáticamente.
El proceso comprueba que la JVM de empaquetado sea Java 21; si Maven se
ejecuta desde otro JDK, define `JDK21_HOME` sin modificar el JDK global:

```powershell
$env:JDK21_HOME = "C:\Ruta\al\jdk-21"
mvn clean package
```

`jpackage` prepara el lanzador y el runtime de Java 21, mientras que Inno
Setup genera el asistente de instalación y actualización. No se necesita WiX
Toolset para esta nueva modalidad. Los instaladores se guardan en
`target/installer/<versión>-<fecha>/`, con un nombre como:

```text
Center-of-Neurodivergence-Setup-1.2.0.exe
```

El compilador de Inno Setup se detecta en `PATH`, `INNO_SETUP_HOME` o en su
ubicación habitual `C:\Program Files (x86)\Inno Setup 6\`.
El repositorio incluye el asistente en `packaging/windows/` y las
comprobaciones de instalación y actualización en GitHub Actions.

## Datos de usuario

Los datos se guardan en la carpeta `CenterOfNeurodivergence`:

- Windows: `%APPDATA%\\CenterOfNeurodivergence\\`
- macOS: `~/Library/Application Support/CenterOfNeurodivergence/`
- Linux: `~/.local/share/CenterOfNeurodivergence/`

La carpeta `apps/` contiene las apps externas instaladas, `themes/` los temas `.tme` instalados y `data/` los datos de cada app.

El registro de aplicaciones externas se guarda en `apps.db` (SQLite), dentro de esa misma carpeta. La tabla `installed_apps` conserva el identificador, nombre, autor, versión, nombre del paquete .ncapp, fecha de instalación, tamaño en bytes y la opción `autorun` (0 = desactivado, 1 = activado). La base de datos es la fuente de verdad: al iniciar solo se cargan los paquetes .ncapp registrados.

En **Ajustes → Gestionar apps → Configurar inicio automático** puedes activar o desactivar el arranque automático de cada app. Las apps con `autorun = 1` se inician al abrir Center of Neurodivergence, pero la pantalla inicial sigue siendo Inicio.

## Inicio automático de aplicaciones

En **Ajustes → Gestionar apps → Configurar inicio automático**, aparece una
lista de todas las aplicaciones externas instaladas, una fila por aplicación.
Cada fila ofrece tres opciones: **No**, **Sí** o **Retrasado (15 segundos)**.

- **No:** la aplicación no se inicia automáticamente.
- **Sí:** se inicia en segundo plano cuando arranca NeuroCenter.
- **Retrasado:** se inicia en segundo plano 15 segundos después de arrancar
  NeuroCenter, sin cambiar de la página Inicio. Si la abres manualmente antes,
  se cancela el arranque programado para evitar duplicados.

El menú guarda todas las elecciones de una vez y las aplica en el próximo
inicio. Las instalaciones anteriores con el antiguo campo booleano
`autorun` se migran automáticamente sin perder sus preferencias ni datos.
Las aplicaciones integradas siguen siendo parte del núcleo y no se incluyen
en las opciones de inicio automático de paquetes externos.

## Aplicaciones externas (.ncapp): un formato para escritorio y Android

**Solo hay un formato de distribución de aplicaciones NeuroCenter: `.ncapp`.**
Funciona como paquete tanto en Windows/escritorio como en Android. Técnicamente contiene
un archivo ZIP/JAR Java 21 con `META-INF/MANIFEST.MF`; esto no significa que el usuario
deba instalarlo como `.jar` ni que existan dos extensiones distintas.

En **Ajustes → Gestionar apps → Instalar aplicación (.ncapp)** de escritorio puedes
seleccionar el archivo. En Android se instala el **mismo archivo `.ncapp`**
desde la gestión de aplicaciones o el gestor de archivos. El instalador comprueba la
compatibilidad declarada antes de permitir su uso.

El manifiesto contiene las plataformas admitidas, el identificador y los puntos de
entrada correspondientes:

```text
Manifest-Version: 1.0
NeuroCenter-App-Id: ejemplo.multiplataforma
NeuroCenter-App-Desktop: true
NeuroCenter-App-Mobile: true
NeuroCenter-Mobile-Class: ejemplo.DemoMobile
NeuroCenter-App-Permissions: network.connect
```

Los flags ausentes equivalen a `false`:
- `true/true`: **el mismo paquete** tiene código para escritorio y Android.
- `true/false`: solo puede ejecutarse en escritorio.
- `false/true`: solo puede ejecutarse en Android.

**No todas las aplicaciones son automáticamente multiplataforma.** El formato es
común, pero cada app debe incluir su implementación de la plataforma que declara.
Para escritorio, un `.ncapp` incluye el proveedor
`META-INF/services/com.neurocenter.api.NeuroApp` y una implementación
`NeuroApp` con Swing. Para Android, incluye `NeuroCenter-Mobile-Class` con
una clase Java 21 que implementa `renderJson()` y `onAction(String)`. El núcleo
Android ejecuta esa clase en un proceso aislado y dibuja su interfaz declarativa.

El ejemplo real `examples/ncapp-demo` declara **ambas plataformas** y GitHub
Actions publica un solo archivo `neurocenter-demo.ncapp` para las dos.
`examples/ncapp-dev-test-kit` es solo móvil, y Teis Aula Sync actualmente
es solo escritorio aunque también se distribuye como `.ncapp`.

Las bibliotecas Java internas siguen usando `JarFile` y `ClassLoader` para abrir
el contenido, sin que esto cambie la extensión instalada. El `.jar` del programa
principal de escritorio tampoco es un paquete de aplicación externa: es el
ejecutable interno que usa el instalador EXE.

### Permisos

Las apps externas solo pueden solicitar los identificadores incluidos en
`NeuroCenter-App-Permissions`; el host muestra **Permitir**, **Denegar**,
**Permitir siempre** o **Denegar siempre** cuando sea necesario. El gestor de
permisos solo muestra los permisos del manifiesto. En escritorio la
política Java 21 limita el acceso; en Android la app Java se ejecuta en un
proceso aislado. El acceso a Agenda desde apps externas requiere además el
plugin Agenda Bridge; conceder `agenda.write` no instala dicho plugin.

Por defecto, una app solo utiliza sus propios datos. Para detalles de
implementación, empaquetado y servicios consulta
[docs/ncapp-mobile-api.md](docs/ncapp-mobile-api.md).

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
    ├── background.png
    ├── center-icon.png
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


## Sistema de plugins (API v1)

NeuroCenter separa las aplicaciones de los plugins:

- **Aplicaciones:** implementan `NeuroApp`, se cargan como JAR externos y conservan su política de permisos.
- **Plugins:** implementan `NeuroPlugin`, se distribuyen como paquetes ZIP `.ncp` y se ejecutan como código de confianza total.

Los plugins se instalan desde **Ajustes → Gestionar plugins**. Pueden registrar servicios, escuchar eventos, contribuir a puntos de extensión y registrar nuevas aplicaciones en el centro. La API de aplicaciones expone `AppContext.services()`, `events()` y `extensions()`.

### Ciclo de vida de un plugin

`onLoad(PluginContext)` → `onEnable()` → `onDisable()` → `onUnload()`.

Un paquete `.ncp` contiene `plugin.json`, `plugin.jar` y, opcionalmente, dependencias JAR en `lib/`. El manifiesto declara `id`, `name`, `version`, `apiVersion` y `entrypoint`.

### Servicios y privilegios

Los plugins tienen `AllPermission`; no existe un diálogo de permisos para ellos. Las aplicaciones no heredan esos permisos: consumen interfaces de servicio a través de un proxy privilegiado. Instala únicamente plugins de confianza, ya que un plugin puede leer/modificar archivos, acceder a la red, cargar código nativo y alterar el propio proceso.

El primer contrato funcional es `AgendaBridgeService`. El plugin Agenda Bridge traduce las solicitudes de una aplicación a `AgendaService`, que actualiza la agenda de forma idempotente usando el origen y el identificador externo de cada tarea.

### Lombok

NeuroCenter utiliza Lombok `1.18.42` como dependencia `provided` y procesador de anotaciones de Maven. Lombok solo interviene durante la compilación; no se incorpora al runtime distribuido.

## Permisos declarados de las aplicaciones externas

Un paquete Java `.ncapp` debe incluir en su `META-INF/MANIFEST.MF` las
capacidades que necesita. Por ejemplo:

```text
NeuroCenter-App-Permissions: network.connect,credentials.access,agenda.write
```

La ausencia de la propiedad equivale a **ningún permiso**. El gestor muestra
únicamente las capacidades declaradas; no permite añadir otras. Si una app
pide una capacidad que no declaró, NeuroCenter la deniega sin mostrar ningún
diálogo. Para usar una capacidad debe solicitarla en el momento oportuno
mediante `AppContext.requestPermission(id)` (escritorio) o mediante la solicitud
al anfitrión (Android).

El diálogo oficial tiene **Permitir**, **Denegar**, **Permitir siempre** y
**Denegar siempre**. En escritorio «Permitir» y «Denegar» duran hasta cerrar
la sesión de la app, las decisiones «siempre» se guardan en SQLite. En Android
la autorización temporal corresponde a una solicitud concreta. Ninguna
decisión puede conceder una capacidad no declarada.

Los APK Android externos declaran su lista por separado mediante el
`<meta-data android:name="com.neurocenter.app.permissions" ...>` de su
`AndroidManifest.xml`. Ver `docs/android-external-apps.md`.

Nota: una app instalada antes de este cambio que no declarase permisos
no tendrá autorizaciones adicionales; para utilizar la versión adaptada
deberá actualizarse/reinstalarse el paquete. Una capacidad de Agenda también
requiere el plugin Agenda Bridge instalado.
