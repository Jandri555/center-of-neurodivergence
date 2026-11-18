package com.neurocenter.api.services;

import java.time.LocalDate;
import java.time.LocalTime;

/** Actividad externa normalizada para importarla en una agenda. */
public record ExternalTask(String externalId, String title, LocalDate dueDate,
                           LocalTime dueTime, String notes, boolean completed) {
    public ExternalTask {
        if (externalId == null || externalId.isBlank()) throw new IllegalArgumentException("externalId es obligatorio.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title es obligatorio.");
        notes = notes == null ? "" : notes;
    }
}
