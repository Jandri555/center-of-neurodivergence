package com.neurocenter.apps.pdfreader;

import com.formdev.flatlaf.util.UIScale;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

/**
 * Supersampling y manejo HiDPI nativo para las páginas PDF.
 */
final class PdfImageQuality {

    private PdfImageQuality() {
    }

    static float renderScale(float displayDpi) {
        // Usa la escala real del sistema operativo detectada por FlatLaf.
        // Aseguramos un mínimo de 2.0x para garantizar precisión al trazar las letras
        // bionic.
        float sysScale = UIScale.getUserScaleFactor();
        return Math.max(2.0f, sysScale);
    }

    static java.awt.Image downsample(BufferedImage source, float renderScale) {
        if (source == null || renderScale <= 1.001f) {
            return source;
        }

        int width = Math.max(1, Math.round(source.getWidth() / renderScale));
        int height = Math.max(1, Math.round(source.getHeight() / renderScale));

        // Creamos una imagen "proxy" del tamaño lógico esperado por el JLabel.
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        // Swing mide con la imagen lógica y usa la variante de alta resolución en HiDPI.
        return new BaseMultiResolutionImage(result, source);
    }
}
