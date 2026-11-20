package com.neurocenter.core;

import android.app.ApplicationExitInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEBUG only: parses a small, safe subset of Android's native tombstone
 * protobuf from ApplicationExitInfo.getTraceInputStream (API 31+).
 * No root, READ_LOGS, external storage or executable code is required.
 *
 * Only the crashing thread's native backtrace is shown; other threads,
 * memory maps, register values and app data are deliberately omitted.
 * See AOSP debuggerd/proto/tombstone.proto.
 */
final class NcappTombstoneSummary {
    private static final int MAX_PROTO_BYTES = 4 * 1024 * 1024;
    private static final int MAX_THREADS = 128;
    private static final int MAX_FRAMES = 15;

    private NcappTombstoneSummary() { }

    static String read(ApplicationExitInfo info) {
        if (Build.VERSION.SDK_INT < 31
                || info.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return "Tombstone: disponible solamente para fallos nativos en Android 12+.\n";
        }
        try (InputStream stream = info.getTraceInputStream()) {
            if (stream == null) return "Tombstone: no disponible (el sistema puede haberla descartado).\n";
            // InputStream.readNBytes exists only on Android API 33+, while
            // native tombstones are available from API 31. Use basic read().
            ByteArrayOutputStream bounded = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (count > MAX_PROTO_BYTES - bounded.size()) {
                    return "Tombstone: demasiado grande para el informe limitado de diagnóstico.\n";
                }
                bounded.write(chunk, 0, count);
            }
            return summarize(bounded.toByteArray());
        } catch (Exception ex) {
            return "Tombstone: no se pudo interpretar (" + ex.getClass().getSimpleName() + ").\n";
        }
    }

    private static String summarize(byte[] data) throws IOException {
        Reader r = new Reader(data, 0, data.length);
        long crashingTid = -1;
        String abortMessage = "";
        String signal = "";
        Map<Long, NativeThread> threads = new LinkedHashMap<>();
        while (r.hasNext()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 6 && wire == 0) {
                crashingTid = r.varint();
            } else if (field == 14 && wire == 2) {
                abortMessage = truncate(r.string(), 320);
            } else if (field == 10 && wire == 2) {
                signal = parseSignal(r.child());
            } else if (field == 16 && wire == 2) {
                if (threads.size() >= MAX_THREADS) {
                    r.skip(wire);
                } else {
                    Reader entry = r.child();
                    long key = -1;
                    NativeThread t = null;
                    while (entry.hasNext()) {
                        int subtag = entry.tag();
                        if ((subtag >>> 3) == 1 && (subtag & 7) == 0) {
                            key = entry.varint();
                        } else if ((subtag >>> 3) == 2 && (subtag & 7) == 2) {
                            t = parseThread(entry.child());
                        } else {
                            entry.skip(subtag & 7);
                        }
                    }
                    if (key >= 0 && t != null) threads.put(key, t);
                }
            } else {
                r.skip(wire);
            }
        }
        StringBuilder out = new StringBuilder("Tombstone nativa de Android (sin datos de memoria):\n");
        if (!signal.isEmpty()) out.append("Señal: ").append(signal).append('\n');
        if (!abortMessage.isEmpty()) out.append("Abort: ").append(safe(abortMessage)).append('\n');
        NativeThread crash = threads.get(crashingTid);
        if (crash == null) {
            out.append("No se encontró el hilo responsable (tid=").append(crashingTid).append(").\n");
            return out.toString();
        }
        out.append("Hilo: ").append(safe(crash.name)).append(" (tid=")
                .append(crashingTid).append(")\n");
        int i = 0;
        for (String frame : crash.frames) {
            out.append('#').append(i++).append(' ').append(frame).append('\n');
        }
        if (i == 0) out.append("Traza del hilo no disponible.\n");
        return out.toString();
    }

    private static String parseSignal(Reader reader) throws IOException {
        String signalName = "";
        long number = -1;
        while (reader.hasNext()) {
            int tag = reader.tag();
            if ((tag >>> 3) == 1 && (tag & 7) == 0) number = reader.varint();
            else if ((tag >>> 3) == 2 && (tag & 7) == 2) signalName = reader.string();
            else reader.skip(tag & 7);
        }
        return safe(truncate(signalName, 32)) + " (" + number + ")";
    }

    private static NativeThread parseThread(Reader reader) throws IOException {
        String name = "";
        List<String> frames = new ArrayList<>();
        while (reader.hasNext()) {
            int tag = reader.tag();
            if ((tag >>> 3) == 2 && (tag & 7) == 2) name = reader.string();
            else if ((tag >>> 3) == 4 && (tag & 7) == 2) {
                if (frames.size() < MAX_FRAMES) frames.add(parseFrame(reader.child()));
                else reader.skip(2);
            } else {
                reader.skip(tag & 7);
            }
        }
        return new NativeThread(truncate(name, 70), frames);
    }

    private static String parseFrame(Reader r) throws IOException {
        long relPc = 0;
        long functionOffset = 0;
        String function = "";
        String library = "";
        while (r.hasNext()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) relPc = r.varint();
            else if (field == 5 && wire == 0) functionOffset = r.varint();
            else if (field == 4 && wire == 2) function = r.string();
            else if (field == 6 && wire == 2) library = r.string();
            else r.skip(wire);
        }
        // Keep just the binary name, not private APK paths or executable maps.
        int slash = library.lastIndexOf('/');
        if (slash >= 0) library = library.substring(slash + 1);
        return "pc 0x" + Long.toHexString(relPc) + " " + safe(truncate(library, 75))
                + (function.isEmpty() ? "" : " (" + safe(truncate(function, 100))
                + "+0x" + Long.toHexString(functionOffset) + ")");
    }

    private static String safe(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static String truncate(String str, int max) {
        return str.length() > max ? str.substring(0, max) + "…" : str;
    }

    private record NativeThread(String name, List<String> frames) { }

    /** Minimal protobuf wire reader, bounded to the current embedded message. */
    private static final class Reader {
        private final byte[] bytes;
        private int pos;
        private final int end;
        Reader(byte[] bytes, int from, int to) {
            this.bytes = bytes;
            this.pos = from;
            this.end = to;
        }
        boolean hasNext() { return pos < end; }
        int tag() throws IOException {
            long value = varint();
            if (value == 0 || (value >>> 3) == 0 || value > Integer.MAX_VALUE) {
                throw new IOException("Invalid protobuf tag");
            }
            return (int)value;
        }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IOException("Truncated varint");
                int b = bytes[pos++] & 255;
                value |= (long)(b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Protobuf varint overflow");
        }
        Reader child() throws IOException {
            long size = varint();
            if (size < 0 || size > end - pos) throw new IOException("Invalid protobuf length");
            int stop = pos + (int)size;
            Reader sub = new Reader(bytes, pos, stop);
            pos = stop;
            return sub;
        }
        String string() throws IOException {
            Reader sub = child();
            return new String(bytes, sub.pos, sub.end - sub.pos, StandardCharsets.UTF_8);
        }
        void skip(int wire) throws IOException {
            switch (wire) {
                case 0 -> varint();
                case 1 -> advance(8);
                case 2 -> child();
                case 5 -> advance(4);
                default -> throw new IOException("Unknown protobuf wire type " + wire);
            }
        }
        void advance(int bytesToSkip) throws IOException {
            if (bytesToSkip > end - pos) throw new IOException("Truncated protobuf field");
            pos += bytesToSkip;
        }
    }
}
