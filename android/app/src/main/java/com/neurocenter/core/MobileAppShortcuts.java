package com.neurocenter.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;

import com.neurocenter.apps.agenda.MainActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Official Android launcher shortcuts for built-in and installed .ncapp apps. */
public final class MobileAppShortcuts {
    public static final String ACTION_OPEN = "com.neurocenter.action.OPEN_INTERNAL_SHORTCUT";
    public static final String EXTRA_APP_ID = "com.neurocenter.extra.INTERNAL_APP_ID";
    public static final String AGENDA_ID = "agenda";
    private static final String PREFIX = "internal:";
    private static final String PREFS = "neurocenter_launcher_usage";

    private MobileAppShortcuts() {}

    public static void recordUse(Context context, String id) {
        if (!AGENDA_ID.equals(id) && !isInstalled(context, id)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(id, 0);
        prefs.edit().putInt(id, count == Integer.MAX_VALUE ? count : count + 1).apply();
        refresh(context);
    }

    private static boolean isInstalled(Context context, String id) {
        try {
            for (NcappManager.InstalledApp app : NcappManager.list(context)) {
                if (app.id().equals(id)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    public static boolean requestHomeShortcut(Context context, String id, String label) {
        if (!AGENDA_ID.equals(id) && !isInstalled(context, id)) return false;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) return false;
        try {
            // The home launcher shows its own confirmation: no silent icon placement.
            return manager.requestPinShortcut(build(context, id, label, 0), null);
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static void onUninstalled(Context context, String id) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager != null) {
            try { manager.disableShortcuts(List.of(PREFIX + id), "Esta aplicación se ha desinstalado."); }
            catch (RuntimeException ignored) { }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
        refresh(context);
    }

    public static void refresh(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        try {
            int limit = manager.getMaxShortcutCountPerActivity();
            if (limit <= 0) return;
            List<Entry> choices = new ArrayList<>();
            choices.add(new Entry(AGENDA_ID, "Agenda", 0));
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (NcappManager.InstalledApp app : NcappManager.list(context)) {
                choices.add(new Entry(app.id(), app.name(), prefs.getInt(app.id(), 0)));
            }
            // Agenda is always offered; the remaining slots track frequently used apps.
            List<Entry> favorites = new ArrayList<>(choices.subList(1, choices.size()));
            favorites.sort(Comparator.comparingInt(Entry::uses).reversed()
                    .thenComparing(Entry::title, String.CASE_INSENSITIVE_ORDER));
            List<ShortcutInfo> dynamic = new ArrayList<>();
            dynamic.add(build(context, AGENDA_ID, "Agenda", 0));
            for (Entry entry : favorites) {
                if (dynamic.size() >= limit) break;
                dynamic.add(build(context, entry.id(), entry.title(), dynamic.size()));
            }
            manager.setDynamicShortcuts(dynamic);
        } catch (IOException | RuntimeException ignored) {
            // A launcher can refuse or rate-limit publication. Never block NeuroCenter.
        }
    }

    private static ShortcutInfo build(Context context, String id, String label, int rank) {
        String title = (label == null || label.isBlank()) ? id : label.trim();
        String shortLabel = title.length() > 24 ? title.substring(0, 23) + "…" : title;
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_APP_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfo.Builder(context, PREFIX + id)
                .setShortLabel(shortLabel)
                .setLongLabel(title)
                .setIcon(Icon.createWithBitmap(icon(id, title)))
                .setIntent(intent)
                .setRank(rank)
                .build();
    }

    private static Bitmap icon(String id, String title) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] palette = {0xFF196F83, 0xFF394EAA, 0xFF6B3FA0, 0xFF9B6331};
        paint.setColor(AGENDA_ID.equals(id) ? 0xFF237E91 :
                palette[(id.hashCode() & 0x7fffffff) % palette.length]);
        canvas.drawRoundRect(4f, 4f, 92f, 92f, 20f, 20f, paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(44f);
        paint.setTextAlign(Paint.Align.CENTER);
        String symbol = AGENDA_ID.equals(id) ? "A" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(symbol, 48f, 48f - (metrics.ascent + metrics.descent) / 2f, paint);
        return bitmap;
    }

    private record Entry(String id, String title, int uses) {}
}
