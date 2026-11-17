package com.neurocenter.apps.agenda;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.util.UUID;

/**
 * Servidor Bluetooth clásico RFCOMM (perfil SPP) de Android.
 * Acepta nuevas conexiones mientras el servicio siga activo y mantiene
 * cada sesión bidireccional hasta que se pierda el enlace o se desconecte.
 */
final class BluetoothSyncServer implements AutoCloseable {

    static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final String SERVICE_NAME = "Center of Neurodivergence Agenda";

    private final BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket activeSocket;
    private volatile boolean closed;

    BluetoothSyncServer(BluetoothAdapter adapter) throws IOException {
        if (adapter == null) throw new IOException("Este dispositivo no dispone de Bluetooth.");
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
    }

    /**
     * Mantiene el servidor disponible durante toda la vida del servicio.
     * Una desconexión remota no apaga Bluetooth: se vuelve a esperar al PC.
     */
    void serveUntilClosed(
            AgendaStore store,
            AgendaSyncProtocol.OperationMerger merger,
            Runnable onAccepted,
            Runnable onConnected,
            Runnable onDisconnected) throws IOException {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            final BluetoothSocket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                if (closed || Thread.currentThread().isInterrupted()) return;
                throw ex;
            }

            if (closed) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // El servidor se ha detenido justo después de accept().
                }
                return;
            }

            activeSocket = socket;
            try (BluetoothSocket client = socket) {
                if (onAccepted != null) onAccepted.run();
                try {
                    AgendaSyncProtocol.runServerSession(
                            client.getInputStream(),
                            client.getOutputStream(),
                            store,
                            merger,
                            () -> !closed && !Thread.currentThread().isInterrupted(),
                            onConnected);
                } catch (IOException ex) {
                    if (closed || Thread.currentThread().isInterrupted()) return;
                    if (onDisconnected != null) onDisconnected.run();
                }
            } finally {
                activeSocket = null;
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        BluetoothSocket socket = activeSocket;
        try {
            if (socket != null) socket.close();
        } finally {
            serverSocket.close();
        }
    }
}
