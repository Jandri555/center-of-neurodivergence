package com.neurocenter.apps.agenda;

import org.usb4java.ConfigDescriptor;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.EndpointDescriptor;
import org.usb4java.Interface;
import org.usb4java.InterfaceDescriptor;
import org.usb4java.LibUsb;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Host USB para Android Open Accessory (AOA).
 *
 * <p>El PC inicia el protocolo AOA, el teléfono se vuelve a enumerar como
 * accesorio y después ambos intercambian el mismo protocolo que Bluetooth.</p>
 *
 * <p>En Windows el dispositivo/interfaz AOA debe tener un controlador que
 * permita acceso a libusb (WinUSB/libusbK). Sin ese controlador, Windows puede
 * reservar la interfaz para su controlador MTP y libusb no podrá abrirla.</p>
 */
final class UsbAoaClient {

    private static final int AOA_VENDOR_ID = 0x18D1;
    private static final int[] AOA_ACCESSORY_PRODUCT_IDS = {
            0x2D00, // Accessory
            0x2D01, // Accessory + ADB
            0x2D04, // Accessory + Audio
            0x2D05  // Accessory + Audio + ADB
    };

    private static final byte REQUEST_TYPE_VENDOR_IN_DEVICE = (byte) 0xC0;
    private static final byte REQUEST_TYPE_VENDOR_OUT_DEVICE = 0x40;
    private static final byte AOA_GET_PROTOCOL = 51;
    private static final byte AOA_SEND_STRING = 52;
    private static final byte AOA_START_ACCESSORY = 53;

    private static final long CONTROL_TIMEOUT_MS = 3_000;
    private static final long BULK_TIMEOUT_MS = 1_000;
    private static final int BULK_BUFFER_SIZE = 16 * 1024;
    private static final long REENUMERATION_TIMEOUT_MS = 30_000;

    private UsbAoaClient() {}

    static void synchronize(AgendaStore store, BooleanSupplier keepAlive, Runnable onConnected, Runnable onRemoteChange) throws IOException {
        if (store == null || !store.isSyncEnabled()) {
            throw new IOException("Activa primero el historial de sincronización de la agenda.");
        }

        Context context = new Context();
        int init = LibUsb.init(context);
        check(init, "No se pudo iniciar libusb");

        try {
            DeviceHandle handle = findAccessory(context);
            if (handle == null) {
                enableAccessoryMode(context);
                handle = waitForAccessory(context);
            }

            if (handle == null) {
                throw new IOException("El teléfono no ha aparecido en modo Android Open Accessory. "
                        + "Comprueba que admite AOA y que Windows/libusb tiene acceso al dispositivo.");
            }

            try {
                EndpointPair endpoints = claimBulkEndpoints(handle);
                try {
                    UsbInputStream input = new UsbInputStream(handle, endpoints.inputEndpoint());
                    UsbOutputStream output = new UsbOutputStream(handle, endpoints.outputEndpoint());
                    AgendaSyncProtocol.runClientSession(
                            input, output, store,
                            operations -> {
                                mergeOnEdt(store, operations);
                                if (onRemoteChange != null) SwingUtilities.invokeLater(onRemoteChange);
                            },
                            keepAlive,
                            onConnected);
                } finally {
                    LibUsb.releaseInterface(handle, endpoints.interfaceNumber());
                }
            } finally {
                LibUsb.close(handle);
            }
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("No se pudo completar la conexión USB AOA: " + ex.getMessage(), ex);
        } finally {
            LibUsb.exit(context);
        }
    }

    private static void enableAccessoryMode(Context context) throws IOException {
        DeviceList devices = new DeviceList();
        int count = LibUsb.getDeviceList(context, devices);
        if (count < 0) check(count, "No se pudieron enumerar los dispositivos USB");

        boolean supportedDeviceFound = false;
        try {
            for (Device device : devices) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                check(LibUsb.getDeviceDescriptor(device, descriptor),
                        "No se pudo leer el descriptor de un dispositivo USB");

                if (isAccessoryProduct(descriptor)) continue;

                DeviceHandle handle = new DeviceHandle();
                int open = LibUsb.open(device, handle);
                if (open != LibUsb.SUCCESS) {
                    continue;
                }

                try {
                    int protocol = getAoaProtocol(handle);
                    if (protocol < 1) continue;
                    supportedDeviceFound = true;

                    sendAoaString(handle, 0, "Center of Neurodivergence");
                    sendAoaString(handle, 1, "Agenda");
                    sendAoaString(handle, 2, "Agenda sync over USB");
                    sendAoaString(handle, 3, "1.0");
                    sendAoaString(handle, 4, "https://github.com/Jandri555/center-of-neurodivergence");

                    ByteBuffer empty = ByteBuffer.allocateDirect(0);
                    int started = LibUsb.controlTransfer(
                            handle,
                            REQUEST_TYPE_VENDOR_OUT_DEVICE,
                            AOA_START_ACCESSORY,
                            (short) 0,
                            (short) 0,
                            empty,
                            CONTROL_TIMEOUT_MS);
                    if (started < 0 && started != LibUsb.ERROR_NO_DEVICE) {
                        check(started, "El teléfono admite AOA, pero no pudo cambiar a modo accesorio");
                    }
                    return;
                } finally {
                    LibUsb.close(handle);
                }
            }
        } finally {
            LibUsb.freeDeviceList(devices, true);
        }

        if (supportedDeviceFound) {
            throw new IOException("El teléfono admite Android Open Accessory, pero no pudo iniciarse el modo accesorio.");
        }
        throw new IOException("No se encontró ningún teléfono USB que responda al protocolo Android Open Accessory. "
                + "Desbloquea el móvil, utiliza un cable de datos y comprueba la compatibilidad AOA.");
    }

    private static int getAoaProtocol(DeviceHandle handle) {
        ByteBuffer protocol = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN);
        int transferred = LibUsb.controlTransfer(
                handle,
                REQUEST_TYPE_VENDOR_IN_DEVICE,
                AOA_GET_PROTOCOL,
                (short) 0,
                (short) 0,
                protocol,
                CONTROL_TIMEOUT_MS);
        if (transferred < 2) return -1;
        protocol.rewind();
        return Short.toUnsignedInt(protocol.getShort());
    }

    private static void sendAoaString(DeviceHandle handle, int index, String value) throws IOException {
        byte[] text = (value + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocateDirect(text.length);
        data.put(text).flip();
        int transferred = LibUsb.controlTransfer(
                handle,
                REQUEST_TYPE_VENDOR_OUT_DEVICE,
                AOA_SEND_STRING,
                (short) 0,
                (short) index,
                data,
                CONTROL_TIMEOUT_MS);
        if (transferred != text.length) {
            throw new IOException("No se pudo enviar la identificación AOA del accesorio (campo " + index + ").");
        }
    }

    private static DeviceHandle waitForAccessory(Context context) throws IOException {
        long deadline = System.nanoTime() + REENUMERATION_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Se canceló la conexión USB.");
            }
            DeviceHandle handle = findAccessory(context);
            if (handle != null) return handle;
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Se interrumpió la espera del teléfono en modo accesorio.", ex);
            }
        }
        return null;
    }

    private static DeviceHandle findAccessory(Context context) throws IOException {
        DeviceList devices = new DeviceList();
        int count = LibUsb.getDeviceList(context, devices);
        if (count < 0) check(count, "No se pudieron enumerar los dispositivos USB");

        DeviceHandle result = null;
        try {
            for (Device device : devices) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS) continue;
                if (!isAccessoryProduct(descriptor)) continue;

                DeviceHandle handle = new DeviceHandle();
                if (LibUsb.open(device, handle) == LibUsb.SUCCESS) {
                    result = handle;
                    break;
                }
            }
        } finally {
            LibUsb.freeDeviceList(devices, true);
        }
        return result;
    }

    private static EndpointPair claimBulkEndpoints(DeviceHandle handle) throws IOException {
        Device device = LibUsb.getDevice(handle);
        ConfigDescriptor config = new ConfigDescriptor();
        int configResult = LibUsb.getActiveConfigDescriptor(device, config);
        if (configResult != LibUsb.SUCCESS) {
            check(LibUsb.getConfigDescriptor(device, (byte) 0, config),
                    "No se pudieron leer los endpoints del accesorio USB");
        }

        int interfaceNumber = -1;
        byte input = 0;
        byte output = 0;
        try {
            outer:
            for (Interface usbInterface : config.iface()) {
                for (InterfaceDescriptor alternate : usbInterface.altsetting()) {
                    byte inEndpoint = 0;
                    byte outEndpoint = 0;

                    for (EndpointDescriptor endpoint : alternate.endpoint()) {
                        if ((endpoint.bmAttributes() & LibUsb.TRANSFER_TYPE_MASK)
                                != LibUsb.TRANSFER_TYPE_BULK) continue;

                        byte address = endpoint.bEndpointAddress();
                        if ((address & LibUsb.ENDPOINT_DIR_MASK) == LibUsb.ENDPOINT_IN) {
                            inEndpoint = address;
                        } else {
                            outEndpoint = address;
                        }
                    }

                    if (inEndpoint != 0 && outEndpoint != 0) {
                        interfaceNumber = Byte.toUnsignedInt(alternate.bInterfaceNumber());
                        input = inEndpoint;
                        output = outEndpoint;
                        break outer;
                    }
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(config);
        }

        if (interfaceNumber < 0) {
            throw new IOException("El teléfono entró en modo accesorio, pero no expone dos endpoints bulk.");
        }

        int claimed = LibUsb.claimInterface(handle, interfaceNumber);
        check(claimed, "No se pudo reservar la interfaz USB. En Windows puede faltar el controlador WinUSB/libusbK.");
        return new EndpointPair(interfaceNumber, input, output);
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
            throw new IOException("Se interrumpió la aplicación de las operaciones recibidas por USB.", ex);
        } catch (InvocationTargetException ex) {
            throw new IOException("No se pudieron aplicar las operaciones recibidas por USB.", ex.getCause());
        }

        if (failure.get() != null) {
            throw new IOException("No se pudieron guardar las operaciones recibidas por USB.", failure.get());
        }
    }

    private static boolean isAccessoryProduct(DeviceDescriptor descriptor) {
        if (unsigned(descriptor.idVendor()) != AOA_VENDOR_ID) return false;
        int productId = unsigned(descriptor.idProduct());
        for (int supported : AOA_ACCESSORY_PRODUCT_IDS) {
            if (productId == supported) return true;
        }
        return false;
    }

    private static int unsigned(short value) {
        return Short.toUnsignedInt(value);
    }

    private static void check(int result, String message) throws IOException {
        if (result == LibUsb.SUCCESS) return;
        String reason = LibUsb.errorName(result);
        if (result == LibUsb.ERROR_ACCESS) {
            throw new IOException(message + ". Acceso denegado por el controlador USB. "
                    + "En Windows, vincula la interfaz Android Open Accessory a WinUSB/libusbK.");
        }
        throw new IOException(message + ": " + reason + " (" + result + ").");
    }

    private record EndpointPair(int interfaceNumber, byte inputEndpoint, byte outputEndpoint) {}

    private static final class UsbInputStream extends InputStream {
        private final DeviceHandle handle;
        private final byte endpoint;
        private final IntBuffer transferred = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();

        private UsbInputStream(DeviceHandle handle, byte endpoint) {
            this.handle = handle;
            this.endpoint = endpoint;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            while (true) {
                int count = read(one, 0, 1);
                if (count < 0) return -1;
                if (count > 0) return one[0] & 0xFF;
                // InputStream.read() cannot report a timeout as a zero byte.
            }
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (target == null) throw new NullPointerException("target");
            if (offset < 0 || length < 0 || length > target.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;

            while (true) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(length, BULK_BUFFER_SIZE));
                transferred.clear();
                int result = LibUsb.bulkTransfer(
                        handle, endpoint, buffer, transferred, BULK_TIMEOUT_MS);
                int count = transferred.get(0);
                if (count > 0) {
                    buffer.position(0);
                    buffer.get(target, offset, Math.min(count, length));
                    return Math.min(count, length);
                }
                if (result == LibUsb.ERROR_TIMEOUT) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Se canceló la sincronización USB.");
                    }
                    continue;
                }
                if (result < 0) check(result, "Error al recibir datos por USB");
                return count;
            }
        }
    }

    private static final class UsbOutputStream extends OutputStream {
        private final DeviceHandle handle;
        private final byte endpoint;
        private final IntBuffer transferred = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();

        private UsbOutputStream(DeviceHandle handle, byte endpoint) {
            this.handle = handle;
            this.endpoint = endpoint;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (source == null) throw new NullPointerException("source");
            if (offset < 0 || length < 0 || length > source.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            int written = 0;
            while (written < length) {
                int chunk = Math.min(length - written, BULK_BUFFER_SIZE);
                ByteBuffer buffer = ByteBuffer.allocateDirect(chunk);
                buffer.put(source, offset + written, chunk).flip();
                transferred.clear();
                int result = LibUsb.bulkTransfer(
                        handle, endpoint, buffer, transferred, BULK_TIMEOUT_MS);
                int count = transferred.get(0);
                if (count > 0) {
                    written += count;
                    continue;
                }
                if (result < 0) check(result, "Error al enviar datos por USB");
                throw new IOException("El accesorio USB no aceptó los datos enviados.");
            }
        }

        @Override
        public void flush() {}
    }
}
