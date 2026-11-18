# Center of Neurodivergence para Android

El APK ya no es solo la Agenda: es el **panel** de escritorio adaptado a móvil.

| Escritorio | Android |
|---|---|
| `MainWindow` con barra lateral | `MainActivity`: Inicio con tarjetas + menú lateral (≡) con las apps abiertas |
| Ajustes generales (⚙) | Menú ⋮ → Cambiar tema / Gestionar temas / Gestionar apps |
| `NeuroApp` (Swing) | `NeuroMobileApp` (View Android) |
| Agenda (`AgendaApp`) | `AgendaMobileApp`, app integrada que usa `AgendaStore` y el protocolo de sincronización |
| Temas `.tme` (FlatLaf) | Mismos `.tme` (mismo `theme.json`), aplicados con `ThemeManager` a vistas Android |
| Apps externas `.jar` | Apps externas `.apk` o `.jar` **con `classes.dex`**, cargadas con `DexClassLoader` |
| `apps.db` (sqlite-jdbc) | `apps.db` (SQLite de Android), misma tabla `installed_apps` |

## Ciclo de vida de una app

Cada app abierta tiene un hilo de trabajo propio, como en escritorio.

* `onStart(ctx)` y `onStop()`: hilo de la app (E/S permitida).
* `createView`, `onResume`, `onPause`, `onActivityResult`, `onRequestPermissionsResult`: hilo principal.
* `createView` puede llamarse varias veces (cambio de tema): hay que descartar las vistas antiguas.
* `onResume`/`onPause` van emparejados y solo ocurren con la app visible y la pantalla en primer plano.
* Códigos de `startActivityForResult` propios, fuera de 9100–9199 (reservados al host).

## Motor de temas

Mismo formato que escritorio. Para Android el tema debe declarar `"compatibility": {"mobile": true}`
(cada flag, si falta, vale `false`). Claves de color usadas en móvil: `background`, `surface`, `text`
(o `foreground`), `primary`, `selection`, `selectionText`, `border`, `button`, `buttonText`,
`buttonPressed`, `input`, `inputText`, `dialog`, `titleBar`, `titleBarText`. Las demás se ignoran.
`appIcons` funciona igual (PNG dentro de `resources/`). Las apps externas leen los colores con
`ctx.themeColor("surface", fallback)` y `ctx.isDarkTheme()`; `"muted"` es el texto secundario.

Si no hay tema guardado, el panel arranca en Claro u Oscuro según el modo del sistema.

## Apps externas

Un paquete es un `.apk` o un `.jar` que contiene `classes.dex`. Un JAR de escritorio con Swing **no** se puede
ejecutar en Android.

Metadatos (APK → `<meta-data>` dentro de `<application>`; JAR → atributos de `META-INF/MANIFEST.MF`):

| Clave | Uso |
|---|---|
| `NeuroCenter-App-Desktop` / `NeuroCenter-App-Mobile` | `true`/`false`. Para instalarse aquí, `Mobile` debe ser `true` |
| `NeuroCenter-App-Class` | Clases `NeuroMobileApp`, separadas por comas |
| `NeuroCenter-App-Id`, `NeuroCenter-App-Author` | Identificador y autor (opcionales) |

Alternativa a `NeuroCenter-App-Class`: `META-INF/services/com.neurocenter.api.NeuroMobileApp`.
El nombre sale de `android:label` (APK) o `Implementation-Title` (JAR), la versión de `versionName` / `Implementation-Version`.

Detalles que importan:

* El API del host se declara `compileOnly(project(":neurocenter-api"))`: no se empaqueta; el `DexClassLoader` tiene como padre el ClassLoader del host.
* Android 14+ exige que el código cargado dinámicamente sea de **solo lectura**: el panel lo marca al instalar y antes de cada carga.
* Los paquetes viven en `apps/` (código en el almacenamiento privado de la app). La fuente de verdad es `apps.db`.
* Instalar pide confirmación explícita: el código externo corre **en el proceso del panel y con sus permisos**, sin aislamiento.
* Instalar y desinstalar se aplican al instante (sin reiniciar). Las clases ya cargadas siguen en memoria hasta que el proceso termine; reinstalar la misma app tras desinstalarla funciona porque usa un `DexClassLoader` nuevo.
* Inicio automático: las apps marcadas arrancan en segundo plano al abrir el panel; la pantalla inicial sigue siendo Inicio.
* `public static volatile boolean UNSAVED_CHANGES` (opcional) pide confirmación al cerrar, como en escritorio.

Ejemplo: `android/example-app` → `gradle -p android :example-app:assembleDebug`, y luego Ajustes → Gestionar apps → Instalar.

## Pruebas manuales sugeridas

1. Abrir Agenda desde Inicio; crear tarea y evento; girar la pantalla (el estado se conserva).
2. Menú ≡: la Agenda aparece en «Apps abiertas»; cerrarla con × y reabrirla.
3. Cambiar a Oscuro con la Agenda abierta: la vista se reconstruye con el nuevo tema.
4. Instalar un `.tme` con `mobile: true` → Aplicar. Uno solo de escritorio debe rechazarse con el mensaje de compatibilidad.
5. Instalar `example-app` (APK). Debe aparecer en Inicio como «App externa». Desinstalarla.
6. Conectar el USB en modo accesorio: el panel debe abrir la Agenda y pedir permiso de accesorio.
7. Back: cierra el menú → vuelve a Inicio → sale de la app.
