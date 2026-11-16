package com.neurocenter.apps.agenda;

import com.fazecast.jSerialComm.SerialPort;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/**
 * Transporte Bluetooth clásico para el escritorio.
 *
 * <p>Windows expone el puerto SPP saliente del móvil como un puerto COM
 * después de emparejarlo y habilitar el servicio de puerto serie. jSerialComm
 * proporciona el stream; el protocolo de sincronización no depende de COM.</p>
 */
final class BluetoothSerialClient {

    private static final int BAUD_RATE = 115_200;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int WRITE_TIMEOUT_MS = 60_000;


    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(BluetoothSerialClient.class);
    private static final String LAST_PORT_KEY = "agenda.bluetooth.lastPort";

    private BluetoothSerialClient() {}

    static String rememberedPortName() {
        return PREFERENCES.get(LAST_PORT_KEY, null);
    }

    static void clearRememberedPort() {
        PREFERENCES.remove(LAST_PORT_KEY);
    }

    static SerialPort[] availablePorts() {
        return SerialPort.getCommPorts();
    }

    static String label(SerialPort port) {
        String description = port.getDescriptivePortName();
        if (description == null || description.trim().isEmpty()) {
            return port.getSystemPortName();
        }
        return port.getSystemPortName() + " — " + description;
    }

    static void synchronize(SerialPort port, AgendaStore store) throws IOException {
        if (port == null) throw new IOException("No se ha seleccionado ningún puerto serie.");
        if (!store.isSyncEnabled()) {
            throw new IOException("Activa primero el historial de sincronización de la agenda.");
        }

        port.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_BLOCKING,
                READ_TIMEOUT_MS,
                WRITE_TIMEOUT_MS);

        if (!port.openPort(5_000)) {
            throw new IOException("No se pudo abrir " + port.getSystemPortName()
                    + ". Comprueba que el puerto Bluetooth no esté ocupado.");
        }

        try {
            AgendaSyncProtocol.runClient(
                    port.getInputStream(),
                    port.getOutputStream(),
                    store,
                    operations -> mergeOnEdt(store, operations));
            PREFERENCES.put(LAST_PORT_KEY, port.getSystemPortName());
        } finally {
            port.closePort();
        }
    }

    private static void mergeOnEdt(AgendaStore store, java.util.List<AgendaOperation> operations)
            throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            store.mergeOperations(operations);
            return;
        }

        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    store.mergeOperations(operations);
                } catch (RuntimeException ex) {
                    failure.set(ex);
                }
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpió la aplicación del historial recibido.", ex);
        } catch (InvocationTargetException ex) {
            throw new IOException("No se pudo aplicar el historial recibido.", ex.getCause());
        }

        if (failure.get() != null) {
            throw new IOException("No se pudo aplicar el historial recibido.", failure.get());
        }
    }
}
