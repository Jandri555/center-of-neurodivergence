package com.neurocenter.apps.pdfreader;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Supersampling común para las páginas PDF.
 * Renderiza hasta 2x la resolución de pantalla y reduce con interpolación bicúbica.
 */
final class PdfImageQuality {

    private static final float MAX_INTERNAL_DPI = 288.0f;
    private static final float MAX_SCALE = 2.0f;

    private PdfImageQuality() {}

    static float renderScale(float displayDpi) {
        if (!Float.isFinite(displayDpi) || displayDpi <= 0.0f) {
            return 1.0f;
        }
        return Math.max(1.0f, Math.min(MAX_SCALE, MAX_INTERNAL_DPI / displayDpi));
    }

    static BufferedImage downsample(BufferedImage source, float renderScale) {
        if (source == null || renderScale <= 1.001f) {
            return source;
        }

        int width = Math.max(1, Math.round(source.getWidth() / renderScale));
        int height = Math.max(1, Math.round(source.getHeight() / renderScale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        source.flush();
        return result;
    }
}
