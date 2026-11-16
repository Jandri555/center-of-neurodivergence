package com.neurocenter.apps.pdfreader;

import com.formdev.flatlaf.util.UIScale;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

/**
 * Supersampling con soporte HiDPI y un límite de resolución para evitar picos de memoria.
 */
final class PdfImageQuality {

    private static final float MAX_INTERNAL_DPI = 288.0f;
    private static final float MAX_RENDER_SCALE = 2.0f;

    private PdfImageQuality() {}

    static float renderScale(float displayDpi) {
        if (!Float.isFinite(displayDpi) || displayDpi <= 0.0f) return 1.0f;

        float systemScale = UIScale.getUserScaleFactor();
        if (!Float.isFinite(systemScale) || systemScale < 1.0f) systemScale = 1.0f;

        // Respeta la escala del monitor, pero no supera 2x ni 288 DPI internos.
        float dpiLimit = MAX_INTERNAL_DPI / displayDpi;
        return Math.max(1.0f, Math.min(MAX_RENDER_SCALE, Math.min(systemScale, dpiLimit)));
    }

    static java.awt.Image downsample(BufferedImage source, float renderScale) {
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

        // La imagen reducida define el tamaño lógico; la original aporta detalle en HiDPI.
        return new BaseMultiResolutionImage(result, source);
    }
}
