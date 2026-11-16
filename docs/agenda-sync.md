# Agenda: sincronización entre escritorio y Android

## Objetivo

La agenda no se sincronizará comparando dos listas de eventos/tareas. Cada dispositivo conserva un historial de operaciones (altas, modificaciones y borrados) y ambos intercambian las operaciones que les falten.

El historial es append-only. No se vacía simplemente al iniciar una transferencia: una operación solo podrá compactarse cuando el otro dispositivo haya confirmado de forma duradera que la ha recibido. Los borrados se representan mediante tombstones para que una copia antigua no pueda resucitar una entrada eliminada.

## Estado de implementación

### Base de datos local de escritorio

La agenda sigue conservando su copia materializada en `agenda.json`, para mantener compatibilidad con el funcionamiento actual.

Al activar **Preparar sincronización**:

- Se crea una línea base con las entradas que ya existían.
- `agenda-operations.jsonl` empieza a registrar operaciones.
- `agenda-sync.json` guarda el identificador estable de este dispositivo y el reloj causal.
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

## Protocolo de transporte

El motor de sincronización no debe depender del medio físico. USB y Bluetooth transportarán exactamente el mismo intercambio de operaciones.

Intercambio previsto:

1. Negociar versión del protocolo e identificar ambos dispositivos.
2. Intercambiar el estado causal y determinar qué operaciones faltan.
3. Transmitir lotes de operaciones con identificadores y validación de integridad.
4. Guardar cada lote recibido de forma duradera e idempotente.
5. Confirmar la recepción y el estado causal.
6. Compactar únicamente operaciones que todos los dispositivos emparejados hayan confirmado.

No se debe borrar el historial por el mero hecho de haber enviado los datos.

## Medios de conexión

### Bluetooth clásico RFCOMM/SPP (primera vía implementada)

El transporte de Bluetooth usa el mismo protocolo de sincronización. Android abre un servidor RFCOMM con un UUID de servicio fijo y el PC se conecta como cliente.

En Android:
1. Activa **Preparar sincronización**.
2. Pulsa **Esperar conexión Bluetooth**.
3. Acepta activar Bluetooth y hacer el teléfono visible durante cinco minutos.
4. Mantén la pantalla de la Agenda abierta mientras se completa el intercambio.

En Windows:
1. Empareja primero el teléfono desde la configuración Bluetooth.
2. En las opciones avanzadas de Bluetooth, crea o habilita un puerto COM saliente para el servicio serie Bluetooth del móvil (SPP).
3. En la Agenda de escritorio, activa el historial, pulsa **Sincronizar Bluetooth** y selecciona el puerto COM saliente.

El escritorio usa jSerialComm para abrir el puerto serie que Windows presenta. La implementación está lista para pruebas, pero todavía necesita validación física con teléfonos y adaptadores concretos. No se debe dar por garantizada la disponibilidad del perfil SPP en todos los modelos ni controladores.

### USB por cable: Android Open Accessory (AOA)

Hay una implementación inicial en ambos extremos:

- El escritorio inicia el protocolo AOA con las peticiones de control, envía los datos identificativos del accesorio, espera la reenumeración del teléfono y abre los endpoints bulk.
- Android detecta el accesorio mediante `UsbManager`, solicita autorización al usuario y utiliza `UsbAccessory` para abrir los flujos de entrada y salida.
- Ambos extremos pasan esos flujos al mismo `AgendaSyncProtocol` que se utiliza por Bluetooth.

En Windows el dispositivo debe ser accesible por libusb. Si el controlador MTP predeterminado se queda la interfaz, puede ser necesario asociar el dispositivo/interfaz AOA a WinUSB o libusbK. Eso puede cambiar la forma en la que Windows trata el móvil y requiere cuidado: no debe cambiarse el controlador de todas las interfaces del teléfono sin comprobar qué interfaz corresponde al accesorio.

La compatibilidad depende del hardware y firmware del teléfono, del cable de datos, del controlador y de que el fabricante implemente AOA. **El código está implementado, pero la conexión no se ha validado con un teléfono físico.** Por tanto, hay que considerarla experimental y no garantizar que funcione en cualquier móvil o PC.

AOA y MTP no funcionan simultáneamente en el mismo enlace; al cambiar entre ambos modos puede ser necesario desconectar y reconectar.

### Bluetooth clásico RFCOMM/SPP: primera vía implementada

Android abre un servidor RFCOMM usando el UUID estándar del perfil Serial Port Profile (SPP). En el escritorio, jSerialComm permite abrir el puerto COM Bluetooth que Windows crea para la conexión saliente.

En Android:
1. Activa **Preparar sincronización**.
2. Pulsa **Esperar conexión Bluetooth**.
3. Acepta activar Bluetooth y hacer el teléfono visible durante cinco minutos.
4. Mantén la pantalla de la Agenda abierta mientras se completa el intercambio.

En Windows:
1. Empareja primero el teléfono desde Configuración → Bluetooth.
2. Habilita o crea un puerto COM saliente para el servicio serie Bluetooth (SPP) del móvil.
3. En la Agenda de escritorio, activa el historial, pulsa **Sincronizar Bluetooth** y selecciona ese puerto COM.

No todos los controladores y fabricantes exponen el perfil SPP de la misma forma. La implementación está compilada, pero necesita pruebas físicas con distintos teléfonos y adaptadores. No se ha probado todavía una transferencia real.

## Aplicación Android

La aplicación Android será independiente del panel Swing y tendrá:

- La misma agenda local y el mismo formato lógico de operaciones.
- La misma identidad de dispositivo y reglas de conflicto.
- Una preferencia de transporte: USB o Bluetooth.
- Emparejamiento explícito y selección del dispositivo.
- Sincronización manual primero; la automatización se podrá añadir más adelante.

## Fases

1. **Motor de operaciones (en curso):** historial local, línea base, operaciones idempotentes, borrados y pruebas de conflictos.
2. **Protocolo común:** mensajes, negociación, intercambio bidireccional, confirmaciones y compactación segura.
3. **Android:** proyecto Gradle y agenda móvil usando el mismo contrato.
4. **Bluetooth:** primera conexión real entre Android y escritorio.
5. **USB AOA:** implementación y pruebas de compatibilidad con dispositivos.
6. **Pruebas de desconexión:** cortes durante transferencia, repetición de lotes, cambios concurrentes, borrados offline y recuperación tras reinicio.

## Referencias técnicas

- Android USB accessory: https://developer.android.com/develop/connectivity/usb/accessory
- Android Open Accessory protocol: https://source.android.com/docs/core/interaction/accessories/protocol
- Android Bluetooth connections: https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- usb4java/libusb: https://github.com/usb4java/usb4java
