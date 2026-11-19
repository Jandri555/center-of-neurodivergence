package com.neurocenter.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Garantiza que solo exista una instancia de escritorio del programa.
 *
 * La instancia principal mantiene un bloqueo de archivo y escucha órdenes de
 * activación en un puerto TCP aleatorio ligado exclusivamente a loopback.
 * Las ejecuciones posteriores envían la orden a la instancia principal y salen.
 */
public final class SingleInstance implements AutoCloseable {

    private static final int ACTIVATION_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MILLIS = 100;
    private static final int CONNECT_TIMEOUT_MILLIS = 250;

    private final Path infoFile;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final ServerSocket serverSocket;
    private final String token;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Object activationMonitor = new Object();
    private Runnable activationHandler;
    private boolean activationPending;
    private Consumer<Path> ncappOpenHandler;
    private final List<Path> pendingNcappOpens = new ArrayList<>();

    private SingleInstance(Path directory, FileChannel channel, FileLock lock) throws IOException {
        this.infoFile = directory.resolve("single-instance.info");
        this.lockChannel = channel;
        this.fileLock = lock;
        this.token = UUID.randomUUID().toString();

        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            this.serverSocket = socket;

            String metadata = socket.getLocalPort() + System.lineSeparator()
                    + token + System.lineSeparator();
            Files.writeString(infoFile, metadata, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            Thread listener = new Thread(this::listen, "single-instance-listener");
            listener.setDaemon(true);
            listener.start();
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Se conserva la excepción original.
            }
            throw ex;
        }
    }

    /**
     * Intenta convertirse en la instancia principal. Si ya existe otra,
     * solicita que se muestre y devuelve null.
     *
     * Si el proceso anterior está terminando, reintenta adquirir el bloqueo
     * antes de decidir que la instancia existente sigue activa.
     */
    public static SingleInstance acquireOrActivate(Path directory) throws IOException {
        return acquireOrActivate(directory, null);
    }

    /** Admite archivos .ncapp lanzados por la asociación de Windows. */
    public static SingleInstance acquireOrActivate(Path directory, Path ncappToOpen) throws IOException {
        Files.createDirectories(directory);
        Path lockFile = directory.resolve("single-instance.lock");
        Path infoFile = directory.resolve("single-instance.info");

        for (int attempt = 0; attempt < ACTIVATION_ATTEMPTS; attempt++) {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = null;
            try {
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException alreadyLockedInThisProcess) {
                    // Otra instancia dentro de esta misma JVM posee el bloqueo.
                }

                if (lock != null) {
                    try {
                        return new SingleInstance(directory, channel, lock);
                    } catch (IOException | RuntimeException ex) {
                        try {
                            lock.release();
                        } catch (IOException ignored) {
                            // Se conserva la excepción original.
                        }
                        try {
                            channel.close();
                        } catch (IOException ignored) {
                            // Se conserva la excepción original.
                        }
                        throw ex;
                    }
                }
            } finally {
                if (lock == null) channel.close();
            }

            if (notifyExistingInstance(infoFile, ncappToOpen)) return null;

            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Se interrumpió la comprobación de la instancia existente.", ex);
            }
        }

        throw new IOException("Ya hay una instancia en ejecución, pero no se ha podido activar su ventana.");
    }

    /** Registra la acción de activación de la ventana principal. */
    public void setActivationHandler(Runnable handler) {
        if (handler == null) throw new IllegalArgumentException("El controlador de activación no puede ser null.");
        synchronized (activationMonitor) {
            activationHandler = handler;
            if (activationPending) {
                activationPending = false;
                handler.run();
            }
        }
    }

    /** Registra el receptor una vez mostrada la ventana; conserva solicitudes previas. */
    public void setNcappOpenHandler(Consumer<Path> handler) {
        if (handler == null) throw new IllegalArgumentException("Falta el controlador .ncapp.");
        synchronized (activationMonitor) {
            ncappOpenHandler = handler;
            List<Path> queued = new ArrayList<>(pendingNcappOpens);
            pendingNcappOpens.clear();
            for (Path file : queued) handler.accept(file);
        }
    }

    private void listen() {
        while (!closed.get()) {
            try (Socket client = serverSocket.accept()) {
                client.setSoTimeout(1500);
                try (BufferedReader reader = new BufferedReader(
                             new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(
                             new java.io.OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
                    String suppliedToken = reader.readLine();
                    if (suppliedToken != null && tokensMatch(suppliedToken)) {
                        String command = reader.readLine();
                        if ("IMPORT_NCAPP".equals(command)) {
                            String file = reader.readLine();
                            if (file == null || file.length() > 4096
                                    || !file.toLowerCase(java.util.Locale.ROOT).endsWith(".ncapp")) {
                                writer.write("DENIED");
                                writer.newLine();
                                writer.flush();
                                continue;
                            }
                            try {
                                dispatchNcappOpen(Path.of(file));
                            } catch (java.nio.file.InvalidPathException ex) {
                                writer.write("DENIED");
                                writer.newLine();
                                writer.flush();
                                continue;
                            }
                        } else if ("ACTIVATE".equals(command)) {
                            dispatchActivation();
                        } else {
                            writer.write("DENIED");
                            writer.newLine();
                            writer.flush();
                            continue;
                        }
                        writer.write("OK");
                        writer.newLine();
                        writer.flush();
                    } else {
                        writer.write("DENIED");
                        writer.newLine();
                        writer.flush();
                    }
                }
            } catch (SocketException ex) {
                if (!closed.get()) ex.printStackTrace();
                return;
            } catch (IOException ex) {
                if (!closed.get()) System.err.println(
                        "No se pudo procesar una orden de activación: " + ex.getMessage());
            }
        }
    }

    private boolean tokensMatch(String supplied) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void dispatchActivation() {
        synchronized (activationMonitor) {
            if (activationHandler == null) activationPending = true;
            else activationHandler.run();
        }
    }

    private void dispatchNcappOpen(Path file) {
        synchronized (activationMonitor) {
            if (ncappOpenHandler == null) pendingNcappOpens.add(file);
            else ncappOpenHandler.accept(file);
        }
    }

    private static boolean notifyExistingInstance(Path infoFile, Path ncappToOpen) {
        if (!Files.isRegularFile(infoFile)) return false;

        try {
            String[] metadata = Files.readString(infoFile, StandardCharsets.UTF_8).split("\\R");
            if (metadata.length < 2) return false;

            int port = Integer.parseInt(metadata[0].trim());
            String token = metadata[1].trim();
            if (port < 1 || port > 65535 || token.isEmpty()) return false;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(1000);
                try (BufferedWriter writer = new BufferedWriter(
                             new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                     BufferedReader reader = new BufferedReader(
                             new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    writer.write(token);
                    writer.newLine();
                    if (ncappToOpen != null) {
                        String requested = ncappToOpen.toAbsolutePath().normalize().toString();
                        if (requested.length() > 4096 || requested.contains("\n")
                                || requested.contains("\r")) return false;
                        writer.write("IMPORT_NCAPP");
                        writer.newLine();
                        writer.write(requested);
                        writer.newLine();
                    } else {
                        writer.write("ACTIVATE");
                        writer.newLine();
                    }
                    writer.flush();
                    return "OK".equals(reader.readLine());
                }
            }
        } catch (IOException | NumberFormatException ex) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // El listener se cerrará al salir del accept().
        }

        try {
            String[] metadata = Files.readString(infoFile, StandardCharsets.UTF_8).split("\\R");
            if (metadata.length >= 2 && token.equals(metadata[1].trim())) {
                Files.deleteIfExists(infoFile);
            }
        } catch (IOException ignored) {
            // El bloqueo sigue siendo la autoridad; el siguiente arranque puede
            // sobrescribir un archivo de información obsoleto.
        }

        try {
            fileLock.release();
        } catch (IOException ex) {
            System.err.println("No se pudo liberar el bloqueo de instancia: " + ex.getMessage());
        }
        try {
            lockChannel.close();
        } catch (IOException ex) {
            System.err.println("No se pudo cerrar el archivo de bloqueo: " + ex.getMessage());
        }
    }
}
