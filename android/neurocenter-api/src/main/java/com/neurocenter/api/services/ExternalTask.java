package com.neurocenter.api.services;

import java.time.LocalDate;
import java.time.LocalTime;

/** Shared task-transfer DTO used by the desktop plugin bridge and the agenda core. */
public record ExternalTask(
        String externalId,
        String title,
        LocalDate dueDate,
        LocalTime dueTime,
        String notes,
        boolean completed
) {
    public ExternalTask {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required.");
        }
        notes = notes == null ? "" : notes;
    }
}
