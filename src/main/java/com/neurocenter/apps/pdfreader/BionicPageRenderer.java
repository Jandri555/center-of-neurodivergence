package com.neurocenter.apps.pdfreader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renderiza la página completa y refuerza el inicio de las palabras sobre el texto original.
 *
 * No reconstruye la página ni tapa el contenido que hay debajo del texto: dibuja los primeros
 * caracteres en negrita encima del renderizado original. Así se conservan imágenes, fondos,
 * columnas y tablas. La tipografía superpuesta es aproximada porque PDFBox no expone una forma
 * sencilla de volver a pintar cada glifo con el mismo aspecto exacto que el PDF original.
 */
final class BionicPageRenderer {

    private BionicPageRenderer() {}

    static BufferedImage render(PDDocument document, int pageIndex, float dpi, double ratio)
            throws IOException {
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi);
        float scale = dpi / 72.0f;

        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    drawBionicPrefixes(graphics, positions, scale, ratio);
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(document);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawBionicPrefixes(Graphics2D graphics, List<TextPosition> positions,
                                           float scale, double ratio) {
        List<TextPosition> word = new ArrayList<>();
        for (TextPosition position : positions) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.isEmpty()) continue;

            if (unicode.codePoints().allMatch(Character::isWhitespace)) {
                drawWord(graphics, word, scale, ratio);
                word.clear();
            } else {
                word.add(position);
            }
        }
        drawWord(graphics, word, scale, ratio);
    }

    private static void drawWord(Graphics2D graphics, List<TextPosition> word,
                                 float scale, double ratio) {
        if (word.isEmpty()) return;

        int codePoints = 0;
        for (TextPosition position : word) {
            codePoints += position.getUnicode().codePointCount(0, position.getUnicode().length());
        }
        int boldCount = Math.max(1, (int) Math.ceil(codePoints * ratio));
        int consumed = 0;

        for (TextPosition position : word) {
            if (consumed >= boldCount) break;

            String glyph = position.getUnicode();
            int glyphPoints = glyph.codePointCount(0, glyph.length());
            int fontSize = Math.max(1, Math.round(position.getFontSizeInPt() * scale));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            graphics.setColor(new Color(0x202020));

            float x = position.getXDirAdj() * scale;
            float baseline = position.getYDirAdj() * scale;
            graphics.drawString(glyph, x, baseline);
            consumed += glyphPoints;
        }
    }
}
