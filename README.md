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

La API de extensiones actual es `NeuroApp` con su contexto `AppContext`. Los JAR deben registrar sus implementaciones mediante el servicio:

`META-INF/services/com.neurocenter.api.NeuroApp`

**Seguridad:** una app externa es código Java ejecutado con los permisos de tu usuario. No existe aislamiento de seguridad entre una app externa y la aplicación principal. Instala únicamente JAR de fuentes de confianza.

## Sincronización de Agenda con Android (en desarrollo)

La Agenda puede preparar un historial de operaciones desde el botón **Preparar sincronización**. Al activarlo, registra altas, modificaciones, cambios de estado y borrados en `agenda-operations.jsonl`, manteniendo `agenda.json` como copia local de trabajo. Los datos que ya existían se incorporan mediante una línea base.

El historial utiliza identificadores idempotentes y relojes causales. Los borrados se conservan como operaciones explícitas para evitar que una entrada antigua reaparezca al sincronizar. Los avisos ya entregados permanecen locales a cada dispositivo.

**Importante:** en esta fase todavía no se transfieren datos entre dispositivos. La aplicación Android y los transportes USB/Bluetooth están pendientes. No borres `agenda-operations.jsonl` ni `agenda-sync.json` mientras la sincronización esté preparada.

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
  "base": "dark",
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

`base` debe ser `light` u `dark` y determina el aspecto base de FlatLaf; `colors` sobrescribe los colores indicados. `appIcons` es opcional y usa los identificadores internos de las apps. Las rutas de iconos deben estar dentro de `resources/`. Se admiten imágenes que Java pueda leer, preferiblemente PNG.

Por seguridad, el instalador rechaza rutas ZIP inseguras, archivos fuera de `theme.json` y `resources/`, más de 200 entradas o más de 20 MB descomprimidos.

## Crear una app externa

Mira `example-app/`. Implementa `com.neurocenter.api.NeuroApp` y registra la clase mediante `META-INF/services/com.neurocenter.api.NeuroApp`.

La app recibe un `AppContext` con acceso a su carpeta de datos, ajustes, barra de estado, ventana principal y navegación mediante `openApp(id)`. Compila contra la aplicación como dependencia `provided`; no empaquetes las librerías de la aplicación dentro del JAR de la app.

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
