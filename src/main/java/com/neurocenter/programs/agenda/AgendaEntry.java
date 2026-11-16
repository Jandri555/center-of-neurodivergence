package com.neurocenter.programs.agenda;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Una tarea o un evento. Se guarda tal cual en agenda.json (fechas y horas como texto ISO).
 *
 * Tarea:  tiene fecha límite (date), hora límite opcional (time), notas, aviso y se puede marcar como hecha.
 * Evento: solo título, fecha y hora.
 *
 * Las entradas guardadas antes de que existieran los eventos no tienen "type" y se leen como tareas.
 */
final class AgendaEntry {
    static final String TASK = "task";
    static final String EVENT = "event";

    String id = UUID.randomUUID().toString();
    String type = TASK;
    String date;            // yyyy-MM-dd (en una tarea es la fecha límite)
    String time;            // HH:mm, o null si no tiene hora
    String title = "";
    String notes = "";
    boolean done;
    boolean remind;         // avisar a la hora indicada (solo tareas)
    boolean notified;       // el aviso ya se mostró

    boolean isEvent() {
        return EVENT.equals(type);
    }

    LocalDate localDate() {
        try {
            return date == null ? null : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    LocalTime localTime() {
        try {
            return time == null || time.isBlank() ? null : LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Momento exacto de la fecha y hora, o null si no tiene hora. */
    LocalDateTime due() {
        LocalDate d = localDate();
        LocalTime t = localTime();
        return d == null || t == null ? null : d.atTime(t);
    }

    /** Una tarea sin hacer cuya fecha límite ya pasó (sin hora, vence al terminar ese día). */
    boolean isOverdue(LocalDateTime now) {
        if (isEvent() || done) return false;
        LocalDate d = localDate();
        if (d == null) return false;
        LocalTime t = localTime();
        LocalDateTime deadline = t != null ? d.atTime(t) : d.atTime(LocalTime.MAX);
        return deadline.isBefore(now);
    }
}
