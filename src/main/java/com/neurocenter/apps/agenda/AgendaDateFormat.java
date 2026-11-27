package com.neurocenter.apps.agenda;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * Formato único de Agenda en escritorio, Android, JSON y sincronización.
 * No admite ISO ni contiene migraciones de formatos anteriores.
 */
public final class AgendaDateFormat {
    private static final DateTimeFormatter SPANISH = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.forLanguageTag("es-ES"))
            .withResolverStyle(ResolverStyle.STRICT);

    private AgendaDateFormat() {}

    public static String store(LocalDate date) {
        return SPANISH.format(date);
    }

    public static String display(LocalDate date) {
        return store(date);
    }

    /** Acepta únicamente dd/MM/aaaa con fecha real y ceros iniciales. */
    public static LocalDate parse(String stored) {
        return LocalDate.parse(stored, SPANISH);
    }

    public static String displayStored(String stored) {
        return stored == null || stored.isBlank() ? "" : display(parse(stored));
    }

    /** El selector de Android numera los meses del 0 al 11. */
    public static LocalDate fromPicker(int year, int zeroBasedMonth, int day) {
        return LocalDate.of(year, zeroBasedMonth + 1, day);
    }
}
