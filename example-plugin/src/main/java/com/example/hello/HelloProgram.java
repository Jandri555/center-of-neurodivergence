package com.example.hello;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/** Plugin de ejemplo: cuenta cuántas veces se ha abierto el app. */
public class HelloProgram implements NeuroApp {

    @Override public String id() { return "hello"; }

    @Override public String name() { return "Hola mundo"; }

    @Override public String description() { return "Plugin de ejemplo que recuerda cuántas veces lo abres."; }

    @Override
    public JComponent createPanel(AppContext ctx) {
        int opens = Integer.parseInt(ctx.getSetting("opens", "0")) + 1;
        ctx.putSetting("opens", String.valueOf(opens));
        ctx.setStatus("Datos de este plugin en: " + ctx.dataDir());
        return new JLabel("<html><div style='text-align:center'>¡Hola! Has abierto este app "
                + opens + (opens == 1 ? " vez." : " veces.") + "</div></html>", SwingConstants.CENTER);
    }
}
