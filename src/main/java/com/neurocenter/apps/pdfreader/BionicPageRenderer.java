package com.neurocenter.apps.pdfreader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.UIManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renderiza la página completa y marca el inicio de las palabras sin alterar sus glifos.
 *
 * No reconstruye la página ni vuelve a pintar el texto con una fuente aproximada: dibuja
 * una línea fina bajo el prefijo de cada palabra. Así conserva exactamente la tipografía,
 * las imágenes, los fondos, las columnas y las tablas del documento original.
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
        int emphasizedCount = Math.max(1, (int) Math.ceil(codePoints * ratio));
        int consumed = 0;
        float startX = Float.POSITIVE_INFINITY;
        float endX = Float.NEGATIVE_INFINITY;
        float baseline = 0f;
        float textHeight = 0f;

        for (TextPosition position : word) {
            if (consumed >= emphasizedCount) break;

            int glyphPoints = position.getUnicode()
                    .codePointCount(0, position.getUnicode().length());
            float x = position.getXDirAdj() * scale;
            float width = position.getWidthDirAdj() * scale;

            startX = Math.min(startX, x);
            endX = Math.max(endX, x + width);
            baseline = position.getYDirAdj() * scale;
            textHeight = Math.max(textHeight, position.getHeightDir() * scale);
            consumed += glyphPoints;
        }

        if (endX <= startX) return;

        // No reescribimos los glifos del PDF con SansSerif: eso crea dobles
        // contornos cuando la fuente, el kerning o la línea base no coinciden.
        // Marcamos el prefijo con una línea fina y dejamos intacta la tipografía.
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) accent = new Color(0x55B8FF);
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(),
                accent.getBlue(), 190));
        graphics.setStroke(new BasicStroke(Math.max(1.0f, 1.15f * scale),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float underlineY = baseline + Math.max(1.0f * scale, textHeight * 0.055f);
        graphics.drawLine(Math.round(startX), Math.round(underlineY),
                Math.round(endX), Math.round(underlineY));
    }
}
