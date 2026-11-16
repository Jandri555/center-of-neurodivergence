# Center of Neurodivergence

Panel de escritorio en Java (Swing + FlatLaf) con varios programas dentro:

| Programa | Qué hace |
|---|---|
| **Agenda** | Dos vistas: *Calendario* (mes + lo que hay cada día) y *Pendientes* (todas las tareas sin hacer, ordenadas por fecha límite, con las vencidas en rojo). Dos tipos de entrada: **tareas** (fecha límite, hora opcional, notas, aviso y casilla de hecha) y **eventos** (solo título, fecha y hora). Los avisos de las tareas saltan con la app abierta, aunque no tengas la agenda a la vista. |
| **Lector PDF** | Vista *Lectura* (texto recompuesto con Bionic Reading: negrita al inicio de cada palabra) y vista *Original*. Fuente, tamaño, interlineado, intensidad de la negrita, ancho de columna y color de fondo ajustables. Recuerda el último PDF y la página. |

Además, cualquier `.jar` que dejes en la carpeta `plugins` aparece como un programa más.

## Requisitos
- JDK 17 o superior
- Maven 3.9 o superior

## Compilar y ejecutar
```
mvn package
java -jar target/center-of-neurodivergence.jar
```
Genera un único `.jar` con todas las librerías dentro.

## Dónde guarda los datos
| Sistema | Carpeta |
|---|---|
| Windows | `%APPDATA%\CenterOfNeurodivergence\` |
| macOS | `~/Library/Application Support/CenterOfNeurodivergence/` |
| Linux | `~/.local/share/CenterOfNeurodivergence/` |

```
CenterOfNeurodivergence/
├── settings.properties     tema claro/oscuro
├── plugins/                aquí van los .jar de programas nuevos
└── data/
    ├── agenda/agenda.json
    ├── pdf-reader/settings.properties
    └── <id-de-tu-programa>/
```
El botón **Carpeta de plugins** del menú lateral abre la carpeta.

## Crear un programa nuevo (plugin)
Mira `example-plugin/`. Resumen:

1. Implementa `com.neurocenter.api.NeuroProgram` (`id`, `name`, `createPanel`; el resto es opcional).
2. Crea el archivo `src/main/resources/META-INF/services/com.neurocenter.api.NeuroProgram`
   con el nombre completo de tu clase en una línea.
3. Compila con la app como dependencia `provided` (no la empaquetes):
   ```
   # una vez, en la carpeta de la app principal:
   mvn install
   # en la carpeta de tu plugin:
   mvn package
   ```
4. Copia el `.jar` de `target/` a la carpeta `plugins` y reinicia la app.

Tu plugin recibe un `ProgramContext` con:
- `dataDir()` carpeta propia para sus archivos
- `getSetting / putSetting` ajustes sencillos que se guardan solos
- `setStatus` mensaje en la barra inferior
- `openProgram(id)` saltar a otro programa
- `window()` ventana principal para diálogos
- `requestAttention()` trae la ventana al frente

Los plugins comparten las librerías de la app (Swing, FlatLaf, Gson, PDFBox) y pueden usar
`com.neurocenter.ui.RoundedPanel` y `BadgeIcon`.
`start()` se ejecuta al arrancar la app (útil para servicios en segundo plano) y
`stop()` al cerrarla. Si un plugin falla al cargar, la app lo avisa y sigue funcionando.

## Estructura del código
```
com.neurocenter
├── Main                       arranque y tema
├── api/                       NeuroProgram, ProgramContext  (lo que ven los plugins)
├── core/                      MainWindow, PluginLoader, AppPaths, PropertiesStore, ThemeManager
├── ui/                        RoundedPanel, BadgeIcon
└── programs/
    ├── agenda/                AgendaProgram, AgendaPanel, AgendaStore, AgendaEntry
    └── pdfreader/             PdfReaderProgram, PdfReaderPanel, BionicText
```
Para añadir un programa integrado nuevo: crea su paquete en `programs/` y regístralo
en el constructor de `MainWindow` (`register(new MiPrograma(), false);`).
El nombre visible de la app está en `core/AppInfo.java`.

## Limitaciones conocidas (v0.1)
- Los avisos de la agenda solo saltan con la aplicación abierta, y solo existen para tareas (los eventos no avisan).
- Los PDF escaneados (imágenes sin texto) solo se pueden ver en la vista *Original*.
- No se admiten PDF protegidos con contraseña.
- Para detectar un plugin nuevo hay que reiniciar la app. En Windows el `.jar` queda bloqueado mientras la app está abierta.
- El reflujo de texto del PDF usa heurísticas simples: en maquetaciones de varias columnas, tablas o notas al pie puede mezclar el orden.
