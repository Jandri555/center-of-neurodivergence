package com.neurocenter.ui;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/** Panel con esquinas redondeadas que sigue los colores del tema activo (claro/oscuro). */
public class RoundedPanel extends JPanel {

    private final int arc;
    private boolean highlighted;

    public RoundedPanel(LayoutManager layout) {
        this(layout, 16);
    }

    public RoundedPanel(LayoutManager layout, int arc) {
        super(layout);
        this.arc = arc;
        setOpaque(false);
    }

    /** Resalta el borde con el color de acento (p. ej. al pasar el ratón). */
    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
        repaint();
    }

    public static Color accent() {
        Color c = UIManager.getColor("Component.accentColor");
        return c != null ? c : new Color(0x4F8BF9);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = UIManager.getColor("TextField.background");
        if (fill == null) fill = getBackground();
        Color border = highlighted ? accent() : UIManager.getColor("Component.borderColor");
        if (border == null) border = Color.GRAY;
        int width = getWidth();
        int height = getHeight();

        if (width > 2 && height > 2) {
            // El trazo de drawRoundRect se centra en su trayectoria. Si esta
            // coincide con x=0/y=0, la mitad exterior queda fuera del área
            // de pintura y Swing la recorta (se nota especialmente en el borde
            // izquierdo de las tarjetas). Dejamos un píxel de margen real.
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc);
            g2.setColor(border);
            g2.drawRoundRect(1, 1, width - 3, height - 3, arc, arc);
        }

        g2.dispose();
        super.paintComponent(g);
    }
}
