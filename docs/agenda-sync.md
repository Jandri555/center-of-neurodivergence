# Agenda: sincronización entre escritorio y Android

## Objetivo

La agenda no se sincroniza comparando dos listas de eventos/tareas. Cada dispositivo conserva un historial de operaciones (altas, modificaciones y borrados), con identidad estable y reloj causal. Android puede actuar como nodo central para varios PCs: registra a cada uno por separado y mantiene sus confirmaciones independientes.

El historial es append-only y cada equipo vinculado tiene su propio vector de confirmación persistido en `agenda-sync.json`. Una operación no se elimina al sincronizar con un solo PC: debe llegar a todos los equipos registrados. En esta implementación no se compacta automáticamente ni siquiera cuando todos han confirmado, para que un equipo nuevo también pueda recibir el historial completo. Los borrados se representan mediante tombstones para que una copia antigua no pueda resucitar una entrada eliminada.

## Estado de implementación

### Base de datos local de escritorio

La agenda sigue conservando su copia materializada en `agenda.json`, para mantener compatibilidad con el funcionamiento actual.

Al activar **Preparar sincronización**:

- Se crea una línea base con las entradas que ya existían.
- `agenda-operations.jsonl` empieza a registrar operaciones.
- `agenda-sync.json` guarda el identificador y nombre estable de este dispositivo, el reloj causal y el reloj confirmado por cada equipo vinculado.
- Se registran altas, cambios, marcado de tareas y borrados.
- El historial se escribe antes de actualizar la copia materializada. Así, si el proceso se interrumpe, la agenda puede reconstruirse desde las operaciones.

La activación es opt-in. Mientras no se active, el comportamiento anterior de `agenda.json` no cambia.

### Formato de operación

Cada operación contiene:

- `operationId`: identificador idempotente `deviceId:counter`.
- `deviceId` y `counter`: identifican el dispositivo que la generó y su secuencia local.
- `lamport` y `vectorClock`: permiten distinguir operaciones causales de cambios simultáneos.
- `type`: `UPSERT` o `DELETE`.
- `entryId`: identidad estable de la tarea o evento.
- `entry`: estado sincronizable completo para `UPSERT`; nulo para `DELETE`.

Enviar varias veces una misma operación no produce duplicados.

### Resolución de conflictos

- Una operación posterior según el reloj causal prevalece sobre su antecesora.
- Dos modificaciones simultáneas de una misma entrada se resuelven determinísticamente para que ambos dispositivos converjan.
- Si hay un borrado y una modificación concurrentes, gana el borrado. Esto evita resucitar una entrada borrada mientras el otro dispositivo estaba desconectado.
- Una operación posterior que ya conoce el borrado puede crear una nueva versión.
- Los indicadores de avisos ya entregados son locales al dispositivo y no se sincronizan. Las preferencias y la configuración del recordatorio sí se sincronizan.

La resolución de dos modificaciones simultáneas de distintos campos es, en esta primera versión, a nivel de entrada completa: prevalece una versión determinista, no se fusionan campos individuales. Antes de dar por terminado el sincronizador hay que decidir si queremos una fusión por campos o mostrar un conflicto al usuario.

### Equipos vinculados y confirmaciones independientes

El móvil puede enlazarse sucesivamente con varios ordenadores (por ejemplo, «PC clase» y «PC casa»). Cada instalación de escritorio conserva un `deviceId` estable, y el móvil la registra por separado la primera vez que sincroniza con ella. El nombre del equipo se anuncia en el protocolo para poder distinguirlo en el registro.

Cada nodo conserva en `agenda-sync.json` una entrada por dispositivo remoto, con su nombre, fecha de primer/último contacto y el vector de operaciones que ese equipo ha confirmado conocer. Cuando un PC se conecta por primera vez, intercambia su historial completo con el móvil; el móvil responde con todo su historial, incluidas las operaciones y los borrados que conserva.

Ejemplo: si el móvil borra una tarea, el tombstone se envía al PC de clase cuando este se conecta. Al confirmar clase, solo avanza el reloj de clase; la operación sigue pendiente para casa. Cuando casa se conecta, recibe el mismo tombstone y entonces avanza su propio reloj. Los dos PCs no tienen que estar conectados a la vez.

La implementación actual **no compacta ni elimina operaciones automáticamente**. Esto es deliberadamente conservador: un equipo que se vincule más adelante también recibe el historial completo. La tabla de confirmaciones ya permite saber qué operaciones están pendientes para cada PC; la compactación segura y la gestión visual para desvincular equipos quedan para una fase posterior. No restaures el `deviceId` de una instalación desde una copia diferente ni clones el archivo `agenda-sync.json` entre PCs: cada ordenador debe tener su identidad propia.

## Protocolo de transporte

El motor de sincronización no debe depender del medio físico. USB y Bluetooth transportarán exactamente el mismo intercambio de operaciones.

Intercambio previsto:

1. Negociar versión del protocolo e intercambiar la identidad estable (ID y nombre) de ambos dispositivos.
2. Intercambiar el estado causal y determinar qué operaciones faltan.
3. Transmitir lotes de operaciones con identificadores y validación de integridad.
4. Guardar cada lote recibido de forma duradera e idempotente.
5. Confirmar la recepción y el estado causal.
6. En una fase futura, compactar únicamente operaciones que todos los dispositivos registrados hayan confirmado. En la versión actual no se compacta el historial.

No se debe borrar el historial por el mero hecho de haber enviado los datos.

### Sesión continua (Bluetooth y USB)

La sincronización ya no termina al intercambiar el historial inicial. Una vez conectados, ambos dispositivos mantienen una sesión abierta y, aproximadamente cada 350 ms:

- Anuncian su reloj causal.
- Envían solo las operaciones que el otro dispositivo todavía no conoce.
- Guardan las operaciones recibidas y actualizan la vista de la agenda.
- Repiten el intercambio mientras la conexión siga abierta.

Por tanto, una tarea, evento, modificación, cambio de estado o borrado guardado en cualquiera de los dispositivos se propaga a los demás cada vez que cada uno se conecta al móvil. Android conserva un reloj de confirmación independiente por PC; sincronizar con uno no marca como recibidas las operaciones en los otros. Los borrados siguen viajando como tombstones; no se comparan listas ni se resucitan entradas eliminadas por una copia antigua.

La ventana de conexión del escritorio es no modal, de modo que se puede seguir usando la agenda mientras sincroniza. **Desconectar** cierra la sesión manualmente. En Android, Bluetooth se ejecuta en un servicio en primer plano independiente de la pantalla. La notificación persistente muestra el estado y ofrece la acción **Desconectar**. La sesión sigue abierta al apagar la pantalla o quitar Agenda de la lista de aplicaciones recientes; solo se detiene desde el botón de la aplicación o desde la acción de la notificación. Si el ordenador pierde el enlace, Android vuelve a esperar una conexión sin apagar el servicio. USB sigue ligado a la actividad y se cierra al salir de ella.

Si se pierde la conexión, el historial local permanece intacto. Al volver a conectar, el intercambio inicial recupera las operaciones pendientes y ambos dispositivos vuelven a converger.

## Medios de conexión

### Bluetooth clásico RFCOMM/SPP (primera vía implementada)

El transporte de Bluetooth usa el mismo protocolo de sincronización. Android abre un servidor RFCOMM con un UUID de servicio fijo y el PC se conecta como cliente.

En Android:
1. Activa **Preparar sincronización**.
2. Pulsa **Esperar conexión Bluetooth**.
3. Acepta activar Bluetooth y hacer el teléfono visible durante cinco minutos para permitir el emparejamiento inicial.
4. El botón pasará a ser **Desconectar Bluetooth** y aparecerá una notificación persistente. No necesitas mantener abierta la pantalla ni conservar Agenda en recientes; no es necesario reiniciar la sincronización por cada cambio.

En Windows:
1. Empareja primero el teléfono desde la configuración Bluetooth.
2. En las opciones avanzadas de Bluetooth, crea o habilita una sola vez un puerto COM saliente para el servicio serie Bluetooth del móvil (SPP).
3. La primera vez, selecciona ese puerto en la Agenda de escritorio. Tras una sincronización correcta, la aplicación recuerda el puerto y lo utiliza automáticamente en las siguientes ocasiones. Si Windows cambia el número o deja de estar disponible, habrá que seleccionarlo de nuevo.

El escritorio usa jSerialComm para abrir el puerto serie que Windows presenta. La implementación está lista para pruebas, pero todavía necesita validación física con teléfonos y adaptadores concretos. No se debe dar por garantizada la disponibilidad del perfil SPP en todos los modelos ni controladores.

### USB por cable: Android Open Accessory (AOA)

Hay una implementación inicial en ambos extremos:

- El escritorio inicia el protocolo AOA con las peticiones de control, envía los datos identificativos del accesorio, espera la reenumeración del teléfono y abre los endpoints bulk.
- Android detecta el accesorio mediante `UsbManager`, solicita autorización al usuario y utiliza `UsbAccessory` para abrir los flujos de entrada y salida.
- Ambos extremos pasan esos flujos al mismo `AgendaSyncProtocol` que se utiliza por Bluetooth.
- La conexión permanece abierta para intercambiar automáticamente cambios nuevos en ambos sentidos hasta desconectar el USB o cerrar la agenda.

En Windows el dispositivo debe ser accesible por libusb. Si el controlador MTP predeterminado se queda la interfaz, puede ser necesario asociar el dispositivo/interfaz AOA a WinUSB o libusbK. Eso puede cambiar la forma en la que Windows trata el móvil y requiere cuidado: no debe cambiarse el controlador de todas las interfaces del teléfono sin comprobar qué interfaz corresponde al accesorio.

La compatibilidad depende del hardware y firmware del teléfono, del cable de datos, del controlador y de que el fabricante implemente AOA. **El código está implementado, pero la conexión no se ha validado con un teléfono físico.** Por tanto, hay que considerarla experimental y no garantizar que funcione en cualquier móvil o PC.

AOA y MTP no funcionan simultáneamente en el mismo enlace; al cambiar entre ambos modos puede ser necesario desconectar y reconectar.

### Bluetooth clásico RFCOMM/SPP: primera vía implementada

Android abre un servidor RFCOMM usando el UUID estándar del perfil Serial Port Profile (SPP). En el escritorio, jSerialComm permite abrir el puerto COM Bluetooth que Windows crea para la conexión saliente.

En Android:
1. Activa **Preparar sincronización**.
2. Pulsa **Esperar conexión Bluetooth**.
3. Acepta activar Bluetooth y hacer el teléfono visible durante cinco minutos.
4. Una vez iniciado el servicio, puedes apagar la pantalla o quitar Agenda de aplicaciones recientes. La notificación «Agenda · Sincronización Bluetooth» indica que el servicio sigue activo y permite desconectarlo sin abrir la aplicación.

En Windows:
1. Empareja primero el teléfono desde Configuración → Bluetooth.
2. Habilita o crea un puerto COM saliente para el servicio serie Bluetooth (SPP) del móvil.
3. En la Agenda de escritorio, activa el historial y pulsa **Conectar Bluetooth**. La primera vez, selecciona el puerto COM saliente; después se recordará y se intentará usar automáticamente.

No todos los controladores y fabricantes exponen el perfil SPP de la misma forma. La implementación está compilada, pero necesita pruebas físicas con distintos teléfonos y adaptadores. No se ha probado todavía una transferencia real.

## Identidad y carpetas de Android

La aplicación Android utiliza el identificador de paquete **`com.neurocenter`**. El código de la Agenda se mantiene organizado en el subpaquete `com.neurocenter.apps.agenda`.

En Android, la raíz de datos es el almacenamiento interno privado de la aplicación:

```text
CenterOfNeurodivergence/
├── apps/       apps externas instaladas (futuro gestor)
├── themes/     temas instalados (futuro gestor)
├── data/
│   └── agenda/
│       ├── agenda.json
│       ├── agenda-operations.jsonl
│       └── agenda-sync.json
└── apps.db     registro de apps (reservado para el futuro gestor)
```

La raíz se resuelve dentro de `files/` de la aplicación Android. Se crean desde el primer arranque las carpetas `apps/`, `themes/` y `data/`; la carpeta `data/agenda/` se crea al inicializar la Agenda. El archivo `apps.db` queda reservado para implementar más adelante el gestor de aplicaciones.

El cambio de identificador de paquete hace que Android trate esta versión como una aplicación nueva, separada de la antigua `com.neurocenter.agenda`. No se incluye migración ni copia de los datos anteriores, tal como se ha decidido para esta etapa.

## Aplicación Android

La aplicación Android será independiente del panel Swing y tendrá:

- La misma agenda local y el mismo formato lógico de operaciones.
- La misma identidad estable por instalación y reglas de conflicto.
- Registro de varios PCs vinculados, con confirmación independiente para cada uno.
- Una preferencia de transporte: USB o Bluetooth.
- Emparejamiento explícito y selección del dispositivo.
- Sincronización manual primero; la automatización se podrá añadir más adelante.

## Fases

1. **Motor de operaciones:** historial local, línea base, operaciones idempotentes, borrados y pruebas de conflictos.
2. **Protocolo común:** intercambio inicial y sesión incremental bidireccional persistente.
3. **Android:** agenda móvil usando el mismo contrato, nodo central multi-PC y confirmaciones independientes por ordenador.
4. **Bluetooth:** implementación de sesión continua; pendiente validar la conexión sostenida con dispositivos físicos.
5. **USB AOA:** implementación de sesión continua; pendiente validar compatibilidad con dispositivos y controladores reales.
6. **Retención multi-PC:** registrar cada equipo y mantener las operaciones pendientes individualmente. La compactación segura y la gestión de equipos vinculados quedan pendientes.
7. **Pruebas físicas:** cortes durante una sesión, cambios concurrentes, borrados offline, reconexión y recuperación tras reinicio.

## Referencias técnicas

- Android USB accessory: https://developer.android.com/develop/connectivity/usb/accessory
- Android Open Accessory protocol: https://source.android.com/docs/core/interaction/accessories/protocol
- Android Bluetooth connections: https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- usb4java/libusb: https://github.com/usb4java/usb4java
