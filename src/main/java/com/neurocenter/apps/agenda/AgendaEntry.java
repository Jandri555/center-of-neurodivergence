package com.neurocenter.apps.agenda;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Una tarea o un evento. Se guarda tal cual en agenda.json (fechas y horas como texto ISO).
 * Tarea:  tiene fecha límite (date), hora límite opcional (time), notas y se puede marcar como hecha.
 * Evento: título, fecha, hora y opciones de aviso (día anterior y/o tiempo antes personalizado).
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
    String externalSource;
    String externalId;
    String notes = "";
    boolean done;

    // Opciones de aviso (exclusivas de eventos)
    boolean remindDayBefore;
    boolean notifiedDayBefore;

    boolean remindCustom;
    int remindBeforeMinutes = 30;
    boolean notifiedCustom;

    // Campos antiguos conservados por compatibilidad con agenda.json previos
    boolean remind;
    boolean notified;

    /** Copia independiente para editar sin alterar la entrada original si se cancela. */
    AgendaEntry copy() {
        AgendaEntry copy = new AgendaEntry();
        copy.id = id;
        copy.type = type;
        copy.date = date;
        copy.time = time;
        copy.title = title;
        copy.externalSource = externalSource;
        copy.externalId = externalId;
        copy.notes = notes;
        copy.done = done;
        copy.remindDayBefore = remindDayBefore;
        copy.notifiedDayBefore = notifiedDayBefore;
        copy.remindCustom = remindCustom;
        copy.remindBeforeMinutes = remindBeforeMinutes;
        copy.notifiedCustom = notifiedCustom;
        copy.remind = remind;
        copy.notified = notified;
        return copy;
    }

    /** Copia el contenido sincronizable, excluyendo el estado local de avisos. */
    AgendaEntry syncCopy() {
        AgendaEntry copy = copy();
        copy.remind = false;
        copy.notified = false;
        copy.notifiedDayBefore = false;
        copy.notifiedCustom = false;
        return copy;
    }

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
            return time == null || time.trim().isEmpty() ? null : LocalTime.parse(time);
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

    /** Momento en el que debe saltar el aviso del día anterior (24h antes del evento). */
    LocalDateTime dayBeforeTrigger() {
        LocalDateTime d = due();
        return d == null ? null : d.minusDays(1);
    }

    /** Momento en el que debe saltar el aviso personalizado. */
    LocalDateTime customTrigger() {
        LocalDateTime d = due();
        return d == null ? null : d.minusMinutes(Math.max(0, remindBeforeMinutes));
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