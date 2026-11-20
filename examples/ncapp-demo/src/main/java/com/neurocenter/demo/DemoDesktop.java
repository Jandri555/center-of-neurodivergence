package com.neurocenter.demo;

import com.neurocenter.api.AppContext;
import com.neurocenter.api.NeuroApp;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Misma lógica Java, interfaz Swing independiente de la móvil. */
public final class DemoDesktop implements NeuroApp {
    private final Counter counter = new Counter();

    @Override public String id() { return "com.neurocenter.demo"; }
    @Override public String name() { return "Demo .ncapp"; }
    @Override public String description() { return "Demostración Java 21 en Windows y Android"; }

    @Override public JComponent createPanel(AppContext context) {
        JPanel root = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Contador: " + counter.value());
        JButton button = new JButton("Sumar uno");
        button.addActionListener(e -> label.setText("Contador: " + counter.increment()));
        root.add(label, BorderLayout.CENTER);
        root.add(button, BorderLayout.SOUTH);
        return root;
    }
}
