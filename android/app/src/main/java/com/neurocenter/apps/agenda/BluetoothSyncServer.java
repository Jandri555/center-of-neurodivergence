package com.neurocenter.apps.agenda;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.util.UUID;

/**
 * Servidor Bluetooth clásico RFCOMM (perfil SPP) de Android.
 * El caller debe solicitar los permisos y hacer la llamada desde un hilo auxiliar.
 */
final class BluetoothSyncServer implements AutoCloseable {

    static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final String SERVICE_NAME = "Center of Neurodivergence Agenda";

    private final BluetoothServerSocket serverSocket;

    BluetoothSyncServer(BluetoothAdapter adapter) throws IOException {
        if (adapter == null) throw new IOException("Este dispositivo no dispone de Bluetooth.");
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
    }

    void waitForOneSync(
            AgendaStore store,
            AgendaSyncProtocol.OperationMerger merger) throws IOException {
        try (BluetoothSocket socket = serverSocket.accept()) {
            AgendaSyncProtocol.runServer(
                    socket.getInputStream(), socket.getOutputStream(), store, merger);
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
