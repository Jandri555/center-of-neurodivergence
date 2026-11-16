package com.neurocenter.ui;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Icono por defecto: cuadrado redondeado de color con la inicial del programa. */
public class BadgeIcon implements Icon {

    private static final Color[] PALETTE = {
            new Color(0x5B8DEF), new Color(0x2FB59A), new Color(0xE08B4F),
            new Color(0x9B6BD6), new Color(0xD6637A), new Color(0x4FA3C7)
    };

    private final String letter;
    private final Color color;
    private final int size;

    public BadgeIcon(String text, String colorKey, int size) {
        this.letter = text == null || text.isBlank() ? "?" : text.substring(0, 1).toUpperCase();
        this.color = PALETTE[Math.floorMod(colorKey.hashCode(), PALETTE.length)];
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(color);
        g2.fillRoundRect(x, y, size, size, size / 3, size / 3);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, size * 0.55f));
        FontMetrics fm = g2.getFontMetrics();
        int tx = x + (size - fm.stringWidth(letter)) / 2;
        int ty = y + (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(letter, tx, ty);
        g2.dispose();
    }

    @Override public int getIconWidth() { return size; }

    @Override public int getIconHeight() { return size; }
}
