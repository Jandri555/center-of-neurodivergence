package com.neurocenter.apps.pdfreader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renderiza la página original y aplica negrita sintética a los prefijos usando
 * los contornos vectoriales de la propia fuente del PDF. No sustituye la tipografía.
 */
final class BionicPageRenderer {

    private BionicPageRenderer() {}

    static java.awt.Image render(PDDocument document, int pageIndex,
                                 float dpi, double ratio) throws IOException {
        Set<GlyphKey> boldGlyphs = collectBoldGlyphs(document, pageIndex, ratio);
        PDFRenderer renderer = new BionicRenderer(document, boldGlyphs);

        float renderScale = PdfImageQuality.renderScale(dpi);
        java.awt.image.BufferedImage highResolution =
                renderer.renderImageWithDPI(pageIndex, dpi * renderScale);
        return PdfImageQuality.downsample(highResolution, renderScale);
    }

    /**
     * Recoge los glifos de cada prefijo en el mismo orden de lectura del flujo PDF.
     * La posición efectiva de cada glifo permite identificarlo después durante el renderizado.
     */
    private static Set<GlyphKey> collectBoldGlyphs(PDDocument document, int pageIndex,
                                                   double ratio) throws IOException {
        List<TextPosition> positions = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                super.processTextPosition(text);
                positions.add(text);
            }
        };
        stripper.setSortByPosition(false);
        stripper.setSuppressDuplicateOverlappingText(false);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(document);

        PDRectangle cropBox = document.getPage(pageIndex).getCropBox();
        float cropOffsetX = cropBox.getLowerLeftX();
        float cropOffsetY = cropBox.getLowerLeftY();

        Set<GlyphKey> result = new HashSet<>();
        List<TextPosition> word = new ArrayList<>();
        TextPosition previous = null;

        for (TextPosition position : positions) {
            String unicode = position.getUnicode();
            if (!isWordText(unicode)) {
                markWord(word, ratio, result, cropOffsetX, cropOffsetY);
                word.clear();
                previous = null;
                continue;
            }

            if (!word.isEmpty() && isVisualWordBreak(previous, position)) {
                markWord(word, ratio, result, cropOffsetX, cropOffsetY);
                word.clear();
            }

            word.add(position);
            previous = position;
        }

        markWord(word, ratio, result, cropOffsetX, cropOffsetY);
        return result;
    }

    private static boolean isWordText(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.codePoints().allMatch(codePoint -> {
            int type = Character.getType(codePoint);
            return Character.isLetterOrDigit(codePoint)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
        });
    }

    /**
     * PDFTextStripper puede recibir dos palabras consecutivas sin un glifo de espacio
     * cuando el PDF las separa mediante posicionamiento. Detectamos ese hueco geométrico.
     */
    private static boolean isVisualWordBreak(TextPosition previous, TextPosition current) {
        if (previous == null) return false;

        float previousDirection = previous.getDir();
        float currentDirection = current.getDir();
        if (Math.abs(previousDirection - currentDirection) > 1.0f) return true;

        float lineDifference = Math.abs(previous.getYDirAdj() - current.getYDirAdj());
        float lineHeight = Math.max(previous.getHeightDir(), current.getHeightDir());
        if (lineDifference > Math.max(1.0f, lineHeight * 0.45f)) return true;

        float gap;
        if (Math.abs(previousDirection - 180.0f) < 1.0f) {
            gap = previous.getXDirAdj()
                    - (current.getXDirAdj() + current.getWidthDirAdj());
        } else if (Math.abs(previousDirection - 90.0f) < 1.0f
                || Math.abs(previousDirection - 270.0f) < 1.0f) {
            gap = current.getYDirAdj()
                    - (previous.getYDirAdj() + previous.getHeightDir());
        } else {
            gap = current.getXDirAdj()
                    - (previous.getXDirAdj() + previous.getWidthDirAdj());
        }

        float spaceWidth = previous.getWidthOfSpace();
        return gap > Math.max(1.0f, spaceWidth * 0.45f);
    }

    private static void markWord(List<TextPosition> word, double ratio, Set<GlyphKey> result,
                                 float cropOffsetX, float cropOffsetY) {
        if (word.isEmpty()) return;

        int totalCodePoints = word.stream()
                .mapToInt(position -> position.getUnicode().codePointCount(
                        0, position.getUnicode().length()))
                .sum();
        int boldCodePoints = Math.max(1, (int) Math.ceil(totalCodePoints * ratio));
        int consumed = 0;

        for (TextPosition position : word) {
            if (consumed >= boldCodePoints) break;

            int[] codes = position.getCharacterCodes();
            if (codes != null && position.getFont() != null) {
                for (int code : codes) {
                    result.add(GlyphKey.of(position.getFont(), code, position.getTextMatrix(),
                            cropOffsetX, cropOffsetY));
                }
            }
            consumed += position.getUnicode().codePointCount(0, position.getUnicode().length());
        }
    }

    private record GlyphKey(PDFont font, int code,
                            int scaleX, int shearY, int shearX,
                            int scaleY, int translateX, int translateY) {
        static GlyphKey of(PDFont font, int code, Matrix matrix) {
            return of(font, code, matrix, 0, 0);
        }

        static GlyphKey of(PDFont font, int code, Matrix matrix,
                           float cropOffsetX, float cropOffsetY) {
            return new GlyphKey(font, code,
                    quantize(matrix.getScaleX()),
                    quantize(matrix.getShearY()),
                    quantize(matrix.getShearX()),
                    quantize(matrix.getScaleY()),
                    quantize(matrix.getTranslateX() + cropOffsetX),
                    quantize(matrix.getTranslateY() + cropOffsetY));
        }

        private static int quantize(float value) {
            return Math.round(value * 1000.0f);
        }
    }

    private static final class BionicRenderer extends PDFRenderer {
        private final Set<GlyphKey> boldGlyphs;

        BionicRenderer(PDDocument document, Set<GlyphKey> boldGlyphs) {
            super(document);
            this.boldGlyphs = boldGlyphs;
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new BionicPageDrawer(parameters, boldGlyphs);
        }
    }

    private static final class BionicPageDrawer extends PageDrawer {
        private final Set<GlyphKey> boldGlyphs;

        BionicPageDrawer(PageDrawerParameters parameters, Set<GlyphKey> boldGlyphs)
                throws IOException {
            super(parameters);
            this.boldGlyphs = boldGlyphs;
        }

        @Override
        protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                     Vector displacement) throws IOException {
            boolean bold = boldGlyphs.contains(GlyphKey.of(font, code, textRenderingMatrix));

            if (!bold) {
                // El texto normal se atenúa para que el prefijo en negrita destaque.
                Graphics2D graphics = (Graphics2D) getGraphics();
                Composite previousComposite = graphics.getComposite();
                try {
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                    super.showFontGlyph(textRenderingMatrix, font, code, displacement);
                } finally {
                    graphics.setComposite(previousComposite);
                }
            } else {
                super.showFontGlyph(textRenderingMatrix, font, code, displacement);
                reinforceOriginalGlyph(textRenderingMatrix, font, code, displacement);
            }
        }

        /**
         * Engrosa el contorno del glifo original. Así se obtiene una negrita sintética
         * con la forma de la fuente incrustada, sin duplicar texto ni desplazar caracteres.
         */
        private void reinforceOriginalGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                            Vector displacement) throws IOException {
            if (!(font instanceof PDVectorFont vectorFont)) return;

            PDGraphicsState state = getGraphicsState();
            RenderingMode mode = state.getTextState().getRenderingMode();

            // No alteramos texto invisible, solo trazado o usado como máscara de recorte.
            if (!mode.isFill() || mode.isClip()) return;

            GeneralPath path = vectorFont.getNormalizedPath(code);
            if (path == null || path.getBounds2D().isEmpty()) return;

            AffineTransform transform = textRenderingMatrix.createAffineTransform();
            transform.concatenate(font.getFontMatrix().createAffineTransform());

            // Replica el ajuste de anchura que aplica el propio PageDrawer de PDFBox.
            if (!font.isEmbedded() && !font.isVertical() && !font.isStandard14()
                    && font.hasExplicitWidth(code)) {
                float fontWidth = font.getWidthFromFont(code);
                if (displacement.getX() > 0 && fontWidth > 0
                        && Math.abs(fontWidth - displacement.getX() * 1000.0f) > 0.0001f) {
                    transform.scale(displacement.getX() * 1000.0f / fontWidth, 1.0);
                }
            }

            Shape glyph = transform.createTransformedShape(path);
            Graphics2D graphics = (Graphics2D) getGraphics().create();
            try {
                graphics.setComposite(state.getNonStrokingJavaComposite());
                graphics.setPaint(getNonStrokingPaint());

                float fontScale = Math.max(Math.abs(textRenderingMatrix.getScaleX()),
                        Math.abs(textRenderingMatrix.getScaleY()));
                float emboldenWidth = Math.max(0.3f, fontScale * 0.065f);

                Shape emboldenedGlyph = new BasicStroke(emboldenWidth,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        .createStrokedShape(glyph);
                graphics.fill(emboldenedGlyph);
            } finally {
                graphics.dispose();
            }
        }
    }
}
