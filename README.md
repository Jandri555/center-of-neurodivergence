# Center of Neurodivergence

Panel de escritorio en Java (Swing + FlatLaf) con varias apps integradas y extensibles.

| App | Qué hace |
|---|---|
| **Agenda** | Calendario, tareas, eventos y avisos mientras la aplicación está abierta. |
| **Lector PDF** | Vista Original y vista Lectura con Bionic Reading. Sus ajustes de lectura (fuente, tamaño, interlineado y fondo) son independientes del tema general. |

## Requisitos
- JDK 17 o superior
- Maven 3.9 o superior

## Compilar y ejecutar
```bash
mvn package
java -jar target/center-of-neurodivergence.jar
```

## Datos de usuario
Los datos se guardan en `CenterOfNeurodivergence`:
- Windows: `%APPDATA%\CenterOfNeurodivergence\`
- macOS: `~/Library/Application Support/CenterOfNeurodivergence/`
- Linux: `~/.local/share/CenterOfNeurodivergence/`

La carpeta `apps/` contiene las apps externas instaladas y `data/` sus datos. La carpeta antigua `plugins/` se migra automáticamente a `apps/` al iniciar.

## Gestionar apps externas
Abre **Ajustes** (⚙) en el panel y entra en **Gestionar apps**. Desde ahí puedes instalar un archivo JAR o programar su desinstalación. Los cambios se aplican al reiniciar, porque una app cargada puede mantener su JAR bloqueado en Windows.

**Seguridad:** una app externa es código Java ejecutado con los permisos de tu usuario. Instala únicamente JAR de fuentes de confianza.

## Crear una app externa
Mira `example-plugin/`. Implementa `com.neurocenter.api.NeuroApp` y registra la clase mediante el servicio:

`META-INF/services/com.neurocenter.api.NeuroApp`

La app recibe un `AppContext` con acceso a su carpeta de datos, ajustes, barra de estado, ventana principal y navegación mediante `openApp(id)`. Compila contra la aplicación como dependencia `provided`; no empaquetes las librerías de la aplicación dentro del JAR de la app.

## Estructura del código
```
com.neurocenter
├── api/       NeuroApp, AppContext
├── core/      MainWindow, AppLoader, AppPaths, PropertiesStore, ThemeManager
├── ui/        RoundedPanel, BadgeIcon
└── apps/      Agenda y Lector PDF
```

## Limitaciones conocidas
- Los avisos de la agenda solo funcionan con la aplicación abierta.
- Los PDF escaneados solo se pueden ver en la vista Original.
- No se admiten PDF protegidos con contraseña.
- Los cambios de instalación y desinstalación requieren reiniciar la aplicación.
- El reflujo de texto del PDF usa heurísticas y puede alterar el orden en documentos complejos.
