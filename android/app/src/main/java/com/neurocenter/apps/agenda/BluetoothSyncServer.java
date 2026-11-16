package com.neurocenter.apps.agenda;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.util.UUID;

/**
 * Servidor Bluetooth clásico RFCOMM (perfil SPP) de Android.
 * Mantiene la sesión bidireccional hasta desconectar o cerrar la actividad.
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

    void waitForOneSync(
            AgendaStore store,
            AgendaSyncProtocol.OperationMerger merger,
            Runnable onConnected) throws IOException {
        try (BluetoothSocket socket = serverSocket.accept()) {
            if (closed) return;
            activeSocket = socket;
            try {
                AgendaSyncProtocol.runServerSession(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        store,
                        merger,
                        () -> !closed && !Thread.currentThread().isInterrupted(),
                        onConnected);
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
