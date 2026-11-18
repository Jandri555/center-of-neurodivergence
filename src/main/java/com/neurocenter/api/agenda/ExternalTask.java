package com.neurocenter.api.agenda;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Immutable task data imported into the NeuroCenter agenda.
 *
 * @param externalId stable identifier assigned by the source system
 * @param title task title
 * @param description plain-text description (not HTML)
 * @param dueDate deadline date, or {@code null} when the source has no deadline
 * @param dueTime deadline time, or {@code null} when the source does not specify a time
 * @param url optional HTTP(S) link to the task in its source system
 */
public record ExternalTask(
        String externalId,
        String title,
        String description,
        LocalDate dueDate,
        LocalTime dueTime,
        URI url
) {
    public ExternalTask {
        externalId = requireText(externalId, "externalId", 512);
        title = requireText(title, "title", 512);
        description = description == null ? "" : description.strip();
        if (description.length() > 20_000) {
            throw new IllegalArgumentException("La descripción no puede superar los 20 000 caracteres.");
        }
        if (dueDate == null && dueTime != null) {
            throw new IllegalArgumentException("Una tarea no puede tener hora límite sin fecha.");
        }
        if (url != null) {
            String scheme = url.getScheme();
            if (url.getHost() == null || url.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("El enlace externo debe ser una URL HTTP(S) válida.");
            }
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String result = Objects.requireNonNull(value, field).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " no puede estar vacío.");
        }
        if (result.length() > maxLength) {
            throw new IllegalArgumentException(field + " no puede superar " + maxLength + " caracteres.");
        }
        return result;
    }
}
