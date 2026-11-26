package com.neurocenter.core;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Responsive dark home screen. Agenda and external apps share the same launcher. */
public final class MobileDashboard {
    private static final int BACK = 0xFF0C1424;
    private static final int SURFACE = 0xFF18243A;
    private static final int TEXT = 0xFFF1F6FD;
    private static final int SUBTLE = 0xFF9CB1C8;
    private static final int ACCENT = 0xFF6BD5E4;
    private MobileDashboard() {}

    public interface Actions {
        void openAgenda();
        void openNcapp(NcappManager.InstalledApp app);
        void openNative(AndroidExternalApps.InstalledApp app);
        void pin(String id, String name);
        void install();
        void manage();
        void permissions();
        void diagnostics();
    }

    public static View create(Activity activity, List<NcappManager.InstalledApp> apps,
                              List<AndroidExternalApps.InstalledApp> nativeApps, Actions actions) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACK);
        LinearLayout page = column(activity);
        page.setPadding(dp(activity, 20), dp(activity, 24), dp(activity, 20), dp(activity, 30));
        scroll.addView(page);

        TextView eyebrow = text(activity, "TU ESPACIO · NEUROCENTER", 12, true, ACCENT);
        page.addView(eyebrow);
        TextView title = text(activity, "Bienvenido a casa", 29, true, TEXT);
        gap(activity, page, 7);
        page.addView(title);
        gap(activity, page, 7);
        page.addView(text(activity, "Tus aplicaciones, tu agenda y tus herramientas, todo en un mismo sitio.",
                15, false, SUBTLE));

        gap(activity, page, 24);
        LinearLayout hero = column(activity);
        hero.setPadding(dp(activity, 18), dp(activity, 17), dp(activity, 18), dp(activity, 17));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF173D52, 0xFF273A63});
        background.setCornerRadius(dp(activity, 20));
        hero.setBackground(background);
        hero.addView(text(activity, "AGENDA INTEGRADA", 12, true, ACCENT));
        gap(activity, hero, 8);
        hero.addView(text(activity, "Organiza tu día", 24, true, TEXT));
        gap(activity, hero, 5);
        hero.addView(text(activity, "Tareas, calendario y sincronización Bluetooth o USB.",
                14, false, 0xFFDBE7F3));
        gap(activity, hero, 14);
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button openAgenda = button(activity, "Abrir Agenda", true);
        openAgenda.setOnClickListener(v -> actions.openAgenda());
        controls.addView(openAgenda, new LinearLayout.LayoutParams(0, dp(activity, 43), 1f));
        gapHorizontal(activity, controls, 8);
        Button pinAgenda = button(activity, "＋ Acceso", false);
        pinAgenda.setContentDescription("Añadir Agenda a la pantalla de inicio de Android");
        pinAgenda.setOnClickListener(v -> actions.pin(MobileAppShortcuts.AGENDA_ID, "Agenda"));
        controls.addView(pinAgenda, new LinearLayout.LayoutParams(0, dp(activity, 43), 1f));
        hero.addView(controls);
        page.addView(hero, full(activity));

        gap(activity, page, 26);
        LinearLayout section = row(activity);
        section.addView(text(activity, "Mis aplicaciones", 20, true, TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = text(activity, String.valueOf(apps.size() + nativeApps.size()), 14, true, ACCENT);
        count.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        section.addView(count);
        page.addView(section);
        gap(activity, page, 8);
        page.addView(text(activity, "Pulsa una tarjeta para abrirla, o + para crear un acceso directo.",
                13, false, SUBTLE));
        gap(activity, page, 12);

        if (apps.isEmpty() && nativeApps.isEmpty()) {
            LinearLayout empty = card(activity);
            empty.addView(text(activity, "Todavía no has instalado apps externas.", 16, true, TEXT));
            gap(activity, empty, 7);
            empty.addView(text(activity, "Instala tu primer paquete .ncapp para verlo aquí.",
                    14, false, SUBTLE));
            page.addView(empty, full(activity));
        } else {
            java.util.ArrayList<View> tiles = new java.util.ArrayList<>();
            for (NcappManager.InstalledApp app : apps) {
                tiles.add(appCard(activity, app.name(), app.version(),
                        () -> actions.openNcapp(app), () -> actions.pin(app.id(), app.name()), true));
            }
            for (AndroidExternalApps.InstalledApp app : nativeApps) {
                tiles.add(appCard(activity, app.name(), "Aplicación Android",
                        () -> actions.openNative(app), null, false));
            }
            for (int i = 0; i < tiles.size(); i += 2) {
                LinearLayout line = row(activity);
                View left = tiles.get(i);
                line.addView(left, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                gapHorizontal(activity, line, 10);
                if (i + 1 < tiles.size()) {
                    line.addView(tiles.get(i + 1), new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                } else {
                    line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
                }
                LinearLayout.LayoutParams params = full(activity);
                params.bottomMargin = dp(activity, 10);
                page.addView(line, params);
            }
        }

        gap(activity, page, 20);
        page.addView(text(activity, "Herramientas", 20, true, TEXT));
        gap(activity, page, 12);
        Button install = button(activity, "＋  Instalar o actualizar .ncapp", true);
        install.setOnClickListener(v -> actions.install());
        page.addView(install, full(activity));
        gap(activity, page, 8);
        Button manage = button(activity, "Gestionar aplicaciones instaladas", false);
        manage.setOnClickListener(v -> actions.manage());
        page.addView(manage, full(activity));
        gap(activity, page, 8);
        Button permissions = button(activity, "Gestionar permisos de aplicaciones", false);
        permissions.setOnClickListener(v -> actions.permissions());
        page.addView(permissions, full(activity));
        gap(activity, page, 8);
        Button diagnostics = button(activity, "Comprobar motor Java 21", false);
        diagnostics.setOnClickListener(v -> actions.diagnostics());
        page.addView(diagnostics, full(activity));

        gap(activity, page, 20);
        page.addView(text(activity, "Los accesos del lanzador aparecen al mantener pulsado el icono "
                + "de NeuroCenter. Para añadir un icono independiente al escritorio de Android, "
                + "usa ＋ Acceso.", 12, false, SUBTLE));
        return scroll;
    }

    private static View appCard(Activity activity, String name, String detail, Runnable open,
                                Runnable pin, boolean ncapp) {
        LinearLayout card = card(activity);
        card.setMinimumHeight(dp(activity, 172));
        card.setOnClickListener(v -> open.run());
        card.setClickable(true);
        card.setFocusable(true);
        String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        TextView glyph = text(activity, initial, 24, true, TEXT);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(shape(0xFF244E6A, dp(activity, 13)));
        card.addView(glyph, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        gap(activity, card, 12);
        card.addView(text(activity, name, 15, true, TEXT));
        gap(activity, card, 4);
        card.addView(text(activity, detail, 12, false, SUBTLE));
        gap(activity, card, 12);
        LinearLayout actions = row(activity);
        Button openButton = button(activity, "Abrir", true);
        openButton.setTextSize(13);
        openButton.setOnClickListener(v -> open.run());
        actions.addView(openButton, new LinearLayout.LayoutParams(0, dp(activity, 39), 1));
        if (ncapp) {
            gapHorizontal(activity, actions, 6);
            Button pinButton = button(activity, "＋", false);
            pinButton.setContentDescription("Añadir " + name + " a la pantalla de inicio de Android");
            pinButton.setOnClickListener(v -> pin.run());
            actions.addView(pinButton, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 39)));
        }
        card.addView(actions);
        return card;
    }

    public static GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    private static Button button(Activity a, String title, boolean primary) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(primary ? BACK : TEXT);
        b.setBackground(shape(primary ? ACCENT : 0xFF2B3C55, dp(a, 12)));
        b.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        b.setMinHeight(dp(a, 42));
        b.setElevation(dp(a, 1));
        return b;
    }
    private static TextView text(Activity a, String value, int size, boolean bold, int color) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private static LinearLayout card(Activity a) {
        LinearLayout box = column(a);
        box.setPadding(dp(a, 15), dp(a, 15), dp(a, 15), dp(a, 15));
        box.setBackground(shape(SURFACE, dp(a, 18)));
        return box;
    }
    private static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private static LinearLayout.LayoutParams full(Activity a) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static void gap(Activity a, LinearLayout parent, int dp) {
        parent.addView(new View(a), new LinearLayout.LayoutParams(1, dp(a, dp)));
    }
    private static void gapHorizontal(Activity a, LinearLayout parent, int dp) {
        parent.addView(new View(a), new LinearLayout.LayoutParams(dp(a, dp), 1));
    }
    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
