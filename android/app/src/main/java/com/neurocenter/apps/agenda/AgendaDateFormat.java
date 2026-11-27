package com.neurocenter.apps.agenda;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Spanish presentation only; AgendaStore and Bluetooth/USB sync retain ISO dates. */
public final class AgendaDateFormat {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.forLanguageTag("es-ES"));

    private AgendaDateFormat() {}

    public static String display(LocalDate date) {
        return date.format(DISPLAY);
    }

    public static String displayStored(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "";
        try {
            return display(LocalDate.parse(isoDate));
        } catch (DateTimeParseException exception) {
            // Never erase an unexpected stored value while rendering a card.
            return isoDate;
        }
    }

    /** Android DatePickerDialog uses zero-based months. */
    public static LocalDate fromPicker(int year, int monthZeroBased, int day) {
        return LocalDate.of(year, monthZeroBased + 1, day);
    }

    /** Stable transport/storage contract shared with desktop Agenda. */
    public static String store(LocalDate date) {
        return date.toString();
    }
}
