package com.neurocenter.sandbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 21 HotSpot-only application class loader. This class is compiled as
 * ordinary Java 17 bytecode, embedded in the signed APK and defined by JNI in
 * HotSpot, not Android ART. The input comes exclusively from the previously
 * SHA-256-verified read-only Binder FD.
 *
 * The isolated process never opens paths to the host's private app data and
 * never creates executable files. A class is decompressed only when requested.
 */
public final class StreamingJarClassLoader extends ClassLoader {
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_CLASS_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
    private final long archiveLength;

    private static native int readArchive(byte[] target, int offset, int size,
                                          long fileOffset) throws IOException;

    public StreamingJarClassLoader(long archiveLength) {
        super(ClassLoader.getSystemClassLoader());
        if (archiveLength <= 0 || archiveLength > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid archive length");
        }
        this.archiveLength = archiveLength;
    }

    private final class ArchiveInput extends InputStream {
        private long cursor;
        private final byte[] oneByte = new byte[1];

        @Override
        public int read() throws IOException {
            return read(oneByte, 0, 1) == -1 ? -1 : oneByte[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, target.length);
            if (len == 0) return 0;
            if (cursor >= archiveLength) return -1;
            int length = (int) Math.min(len, archiveLength - cursor);
            int n = readArchive(target, off, length, cursor);
            if (n <= 0) throw new IOException("The verified JAR FD was truncated");
            cursor += n;
            return n;
        }
    }

    private byte[] entryBytes(String requested, int maxSize) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ArchiveInput())) {
            ZipEntry entry;
            int entries = 0;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Too many JAR entries");
                if (!entry.isDirectory() && requested.equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        if (output.size() > maxSize - n) {
                            throw new IOException("Oversized JAR entry: " + requested);
                        }
                        output.write(buffer, 0, n);
                    }
                    return output.toByteArray();
                }
                zip.closeEntry();
            }
        }
        return null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new ClassNotFoundException("Invalid class name: " + name);
        }
        try {
            byte[] data = entryBytes(name.replace('.', '/') + ".class", MAX_CLASS_BYTES);
            if (data == null) throw new ClassNotFoundException(name);
            return defineClass(name, data, 0, data.length);
        } catch (IOException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("..")) {
            return null;
        }
        // The parent loader may initialize broken/incomplete JRT URL providers
        // on mobile JDKs. Search the verified ncapp for its own resources first.
        // Java class loading still delegates to the parent in loadClass().
        try {
            byte[] data = entryBytes(name, MAX_RESOURCE_BYTES);
            if (data != null) return new ByteArrayInputStream(data);
        } catch (IOException ignored) {
            return null;
        }
        return super.getResourceAsStream(name);
    }
}
