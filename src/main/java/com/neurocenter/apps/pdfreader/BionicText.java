package com.neurocenter.apps.pdfreader;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepara el texto extraído de un PDF para leerlo con Bionic Reading. */
final class BionicText {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");

    private BionicText() {}

    /**
     * Convierte el texto "en bruto" de una página (con salto de línea al final de cada renglón)
     * en párrafos: une las líneas, recompone palabras partidas con guion y separa
     * párrafos y títulos con una heurística sencilla.
     */
    static List<String> paragraphs(String raw) {
        String[] lines = raw.replace("\r", "").split("\n");
        int max = 0;
        for (String l : lines) max = Math.max(max, l.strip().length());

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) {
                flush(result, current);
                continue;
            }
            if (current.length() > 0) {
                if (current.charAt(current.length() - 1) == '-' && Character.isLowerCase(t.charAt(0))) {
                    current.setLength(current.length() - 1); // palabra partida a final de línea
                } else {
                    current.append(' ');
                }
            }
            current.append(t);

            char end = t.charAt(t.length() - 1);
            boolean sentenceEnd = ".?!…\")»”".indexOf(end) >= 0 || end == ':';
            boolean shortLine = t.length() < max * 0.7;
            boolean looksLikeHeading = !sentenceEnd && t.length() < max * 0.45
                    && end != ',' && end != ';' && end != '-';
            if ((sentenceEnd && shortLine) || looksLikeHeading) flush(result, current);
        }
        flush(result, current);
        return result;
    }

    private static void flush(List<String> out, StringBuilder sb) {
        if (sb.length() > 0) {
            out.add(sb.toString().strip());
            sb.setLength(0);
        }
    }

    /**
     * Añade el texto al documento poniendo en negrita el principio de cada palabra.
     * @param ratio fracción de letras que se ponen en negrita (p. ej. 0.5); mínimo una letra
     */
    static void append(StyledDocument doc, String text, double ratio, AttributeSet normal, AttributeSet strong)
            throws BadLocationException {
        Matcher m = WORD.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) doc.insertString(doc.getLength(), text.substring(last, m.start()), normal);
            String word = m.group();
            int letters = word.codePointCount(0, word.length());
            int bold = Math.min(letters, Math.max(1, (int) Math.ceil(letters * ratio)));
            int cut = word.offsetByCodePoints(0, bold);
            doc.insertString(doc.getLength(), word.substring(0, cut), strong);
            if (cut < word.length()) doc.insertString(doc.getLength(), word.substring(cut), normal);
            last = m.end();
        }
        if (last < text.length()) doc.insertString(doc.getLength(), text.substring(last), normal);
    }
}
