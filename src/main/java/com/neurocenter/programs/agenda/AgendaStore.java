package com.neurocenter.programs.agenda;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Guarda y recupera las entradas de la agenda en un archivo JSON. */
final class AgendaStore {

    private static final Type LIST_TYPE = new TypeToken<List<AgendaEntry>>() {}.getType();
    private static final Comparator<AgendaEntry> ORDER =
            Comparator.comparing((AgendaEntry e) -> e.time == null ? "" : e.time)
                    .thenComparing(e -> e.title == null ? "" : e.title.toLowerCase());

    /** Por fecha límite (las que no tienen hora, al final de su día) y luego por título. */
    private static final Comparator<AgendaEntry> BY_DEADLINE =
            Comparator.comparing((AgendaEntry e) -> e.localDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(e -> e.localTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(e -> e.title == null ? "" : e.title.toLowerCase());

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<AgendaEntry> entries = new ArrayList<>();

    AgendaStore(Path file) {
        this.file = file;
    }

    void load() {
        entries.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            List<AgendaEntry> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            if (loaded != null) entries.addAll(loaded);
        } catch (JsonParseException | IOException e) {
            // Archivo dañado: lo apartamos (no se pierde nada) y empezamos con la agenda vacía.
            try {
                Files.move(file, file.resolveSibling("agenda.corrupto-" + System.currentTimeMillis() + ".json"));
            } catch (IOException ignored) {
                // si ni siquiera se puede apartar, seguimos igualmente
            }
        }
    }

    /** Escritura segura: primero a un archivo temporal y luego se sustituye el original. */
    void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(entries, LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<AgendaEntry> all() {
        return Collections.unmodifiableList(entries);
    }

    List<AgendaEntry> on(LocalDate day) {
        return entries.stream().filter(e -> day.equals(e.localDate())).sorted(ORDER).toList();
    }

    /** Todas las tareas sin hacer, la de fecha límite más cercana primero. */
    List<AgendaEntry> pendingTasks() {
        return entries.stream().filter(e -> !e.isEvent() && !e.done).sorted(BY_DEADLINE).toList();
    }

    void add(AgendaEntry entry) {
        entries.add(entry);
    }

    void remove(AgendaEntry entry) {
        entries.remove(entry);
    }
}
