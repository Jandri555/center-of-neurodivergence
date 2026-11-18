package com.neurocenter.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Icono por defecto: cuadrado redondeado de color con la inicial de la app (equivale a BadgeIcon de escritorio). */
public final class BadgeDrawable extends Drawable {

    private static final int[] PALETTE = {
            0xFF5B8DEF, 0xFF2FB59A, 0xFFE08B4F, 0xFF9B6BD6, 0xFFD6637A, 0xFF4FA3C7
    };

    private final String letter;
    private final int size;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BadgeDrawable(String text, String colorKey, int sizePx) {
        this.letter = text == null || text.trim().isEmpty()
                ? "?" : text.trim().substring(0, 1).toUpperCase();
        this.size = sizePx;
        String key = colorKey == null ? "" : colorKey;
        background.setColor(PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)]);
        foreground.setColor(Color.WHITE);
        foreground.setTypeface(Typeface.DEFAULT_BOLD);
        foreground.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float radius = Math.min(b.width(), b.height()) / 3f;
        canvas.drawRoundRect(new RectF(b), radius, radius, background);

        foreground.setTextSize(Math.min(b.width(), b.height()) * 0.55f);
        Paint.FontMetrics fm = foreground.getFontMetrics();
        float baseline = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, b.exactCenterX(), baseline, foreground);
    }

    @Override public int getIntrinsicWidth() { return size; }

    @Override public int getIntrinsicHeight() { return size; }

    @Override
    public void setAlpha(int alpha) {
        background.setAlpha(alpha);
        foreground.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        background.setColorFilter(colorFilter);
        foreground.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
