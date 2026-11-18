package com.example.hello;

import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neurocenter.api.NeuroMobileApp;
import com.neurocenter.api.NeuroMobileContext;

/** App externa mínima: cuenta cuántas veces se ha abierto y se adapta al tema activo. */
public final class HelloMobileApp implements NeuroMobileApp {

    private volatile NeuroMobileContext context;
    private volatile int opens;

    @Override public String id() { return "hello"; }

    @Override public String name() { return "Hola mundo"; }

    @Override public String description() { return "App externa de ejemplo instalada desde un APK."; }

    @Override public String version() { return "1.0.0"; }

    /** Hilo de trabajo de la app: aquí se puede leer/escribir en disco sin bloquear la interfaz. */
    @Override
    public void onStart(NeuroMobileContext context) {
        this.context = context;
        int previous;
        try {
            previous = Integer.parseInt(context.getSetting("opens", "0"));
        } catch (NumberFormatException ex) {
            previous = 0;
        }
        opens = previous + 1;
        context.putSetting("opens", String.valueOf(opens));
    }

    /** Hilo principal. Puede llamarse varias veces (p. ej., al cambiar de tema). */
    @Override
    public View createView(NeuroMobileContext ctx, LayoutInflater inflater, ViewGroup parent) {
        int text = ctx.themeColor("text", Color.BLACK);
        int muted = ctx.themeColor("muted", Color.GRAY);
        float density = ctx.applicationContext().getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);

        LinearLayout box = new LinearLayout(ctx.activity());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx.activity());
        title.setText("¡Hola desde una app externa!");
        title.setTextSize(22);
        title.setTextColor(text);
        title.setGravity(Gravity.CENTER);

        TextView detail = new TextView(ctx.activity());
        detail.setText("Plataforma: " + ctx.platform().label()
                + "\nEsta app se ha abierto " + opens + (opens == 1 ? " vez." : " veces.")
                + "\nTema oscuro: " + (ctx.isDarkTheme() ? "sí" : "no")
                + "\n\nDatos en:\n" + ctx.dataDir());
        detail.setTextColor(muted);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, pad / 2, 0, 0);

        box.addView(title);
        box.addView(detail);
        ctx.setStatus("Hola mundo cargada correctamente.");
        return box;
    }
}
