package com.neurocenter.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neurocenter.api.NeuroPlatform;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Catálogo y aplicación de temas integrados y temas externos .tme en Android.
 *
 * Usa el mismo formato theme.json que el escritorio (mismas claves de color y los mismos
 * flags de compatibilidad). Un tema solo se puede aplicar aquí si declara
 * {@code "compatibility": {"mobile": true}}.
 */
public final class ThemeManager {

    private static final String TAG = "ThemeManager";
    private static final Gson GSON = new Gson();
    private static final Map<String, ThemeInfo> THEMES = new LinkedHashMap<>();

    private static Context appContext;
    private static ThemeInfo activeTheme;
    private static Palette activePalette;

    /** Claves de theme.json que el host móvil entiende (subconjunto de las de escritorio). */
    private static final String[] COLOR_KEYS = {
            "background", "surface", "text", "primary", "selection", "selectionText", "border",
            "button", "buttonText", "buttonPressed", "input", "inputText", "dialog",
            "titleBar", "titleBarText"
    };

    /** Colores resueltos (tema + valores por defecto de su base clara u oscura). */
    public static final class Palette {
        public final boolean dark;
        /** true si el fondo de los diálogos es oscuro (decide la variante de diálogo del sistema). */
        public final boolean dialogDark;
        public final int background, surface, text, primary, selection, selectionText, border,
                button, buttonText, buttonPressed, input, inputText, dialog, titleBar, titleBarText,
                muted;
        private final Map<String, Integer> values;

        private Palette(Map<String, Integer> v) {
            this.values = v;
            background = v.get("background");
            surface = v.get("surface");
            text = v.get("text");
            primary = v.get("primary");
            selection = v.get("selection");
            selectionText = v.get("selectionText");
            border = v.get("border");
            button = v.get("button");
            buttonText = v.get("buttonText");
            buttonPressed = v.get("buttonPressed");
            input = v.get("input");
            inputText = v.get("inputText");
            dialog = v.get("dialog");
            titleBar = v.get("titleBar");
            titleBarText = v.get("titleBarText");
            muted = blend(text, background, 0.55f);
            v.put("muted", muted);
            v.put("foreground", text);
            dark = luminance(background) < 0.5;
            dialogDark = luminance(dialog) < 0.5;
        }

        /** Color por clave de theme.json (más "muted"); {@code fallback} si no existe. */
        public int color(String key, int fallback) {
            Integer c = key == null ? null : values.get(key);
            return c != null ? c : fallback;
        }
    }

    public static final class ThemeInfo {
        public final String id, name, author, version, base;
        /** null en los temas integrados. */
        public final Path directory;
        public final JsonObject colors, appIcons;
        public final PlatformCompatibility.Targets compatibility;

        ThemeInfo(String id, String name, String author, String version, String base,
                  Path directory, JsonObject colors, JsonObject appIcons,
                  PlatformCompatibility.Targets compatibility) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.version = version;
            this.base = base;
            this.directory = directory;
            this.colors = colors;
            this.appIcons = appIcons;
            this.compatibility = compatibility;
        }

        public boolean builtIn() { return directory == null; }

        @Override public String toString() { return name; }
    }

    private ThemeManager() {}

    /** Debe llamarse una vez al arrancar, antes de cualquier otra operación. */
    public static synchronized void init(Context context) {
        appContext = context.getApplicationContext();
        if (activePalette == null) activePalette = buildPalette("light", new JsonObject());
    }

    public static synchronized List<ThemeInfo> availableThemes() {
        refreshThemes();
        return new ArrayList<>(THEMES.values());
    }

    public static synchronized void refreshThemes() {
        THEMES.clear();
        addBuiltIn("light", "Claro", "light");
        addBuiltIn("dark", "Oscuro", "dark");
        if (appContext == null) return;
        Path root = AppPaths.themesDir(appContext);
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Path manifest = dir.resolve("theme.json");
                if (!Files.isRegularFile(manifest)) continue;
                try (Reader reader = Files.newBufferedReader(manifest)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    ThemeInfo info = parseTheme(json, dir);
                    if (info != null && !THEMES.containsKey(info.id)) THEMES.put(info.id, info);
                } catch (Exception ex) {
                    Log.w(TAG, "Tema ignorado (" + dir.getFileName() + "): " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            Log.w(TAG, "No se pudieron leer los temas: " + ex.getMessage());
        }
    }

    private static void addBuiltIn(String id, String name, String base) {
        // Los temas integrados existen en ambas plataformas y no llevan colores propios:
        // usan los valores por defecto de su base (que replican los del escritorio).
        THEMES.put(id, new ThemeInfo(id, name, AppInfo.NAME, "1.0", base, null,
                new JsonObject(), new JsonObject(), new PlatformCompatibility.Targets(true, true)));
    }

    private static ThemeInfo parseTheme(JsonObject json, Path dir) throws IOException {
        if (json == null) return null;
        String id = string(json, "id");
        String name = string(json, "name");
        String base = string(json, "base");
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,48}")
                || !dir.getFileName().toString().equals(id)
                || "light".equals(id) || "dark".equals(id)
                || name == null || name.trim().isEmpty()
                || !("light".equals(base) || "dark".equals(base))) return null;
        PlatformCompatibility.Targets compatibility = PlatformCompatibility.fromThemeManifest(json);
        return new ThemeInfo(id, name, stringOr(json, "author", "Desconocido"),
                stringOr(json, "version", "1.0"), base, dir,
                object(json, "colors"), object(json, "appIcons"), compatibility);
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        String value = string(obj, key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static JsonObject object(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /**
     * Aplica un tema. Si el id no existe se usa «light». Lanza IllegalArgumentException
     * si el tema no declara compatibilidad con móvil (no cambia nada en ese caso).
     * Después de llamarlo, el host debe reconstruir las vistas.
     */
    public static synchronized void apply(String themeId) {
        refreshThemes();
        ThemeInfo theme = THEMES.get(themeId);
        if (theme == null) theme = THEMES.get("light");
        try {
            theme.compatibility.require(NeuroPlatform.MOBILE, "el tema «" + theme.name + "»");
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        activePalette = buildPalette(theme.base, theme.colors);
        activeTheme = theme;
    }

    public static synchronized Palette palette() {
        if (activePalette == null) activePalette = buildPalette("light", new JsonObject());
        return activePalette;
    }

    public static synchronized String activeThemeId() {
        return activeTheme == null ? "light" : activeTheme.id;
    }

    // ------------------------------------------------------------------ colores

    private static Palette buildPalette(String base, JsonObject colors) {
        Map<String, Integer> v = defaults("dark".equals(base));

        // En escritorio «foreground» y «text» son claves distintas; aquí hay un único color de texto.
        Integer foreground = parseColor(colors.get("foreground"));
        if (foreground != null) v.put("text", foreground);

        for (String key : COLOR_KEYS) {
            Integer color = parseColor(colors.get(key));
            if (color != null) v.put(key, color);
        }
        // Si el tema solo cambia el texto principal, los textos derivados lo siguen.
        if (parseColor(colors.get("buttonText")) == null) v.put("buttonText", v.get("text"));
        if (parseColor(colors.get("inputText")) == null) v.put("inputText", v.get("text"));
        if (parseColor(colors.get("titleBarText")) == null) v.put("titleBarText", v.get("text"));
        return new Palette(v);
    }

    private static Map<String, Integer> defaults(boolean dark) {
        Map<String, Integer> v = new LinkedHashMap<>();
        if (dark) {
            // Carbón profundo, igual que el tema Oscuro del escritorio.
            v.put("background", 0xFF181818);
            v.put("surface", 0xFF202020);
            v.put("text", 0xFFE6E6E6);
            v.put("primary", 0xFF4F8BF9);
            v.put("selection", 0xFF2D4373);
            v.put("selectionText", 0xFFFFFFFF);
            v.put("border", 0xFF3A3A3A);
            v.put("button", 0xFF292929);
            v.put("buttonText", 0xFFE6E6E6);
            v.put("buttonPressed", 0xFF3A3A3A);
            v.put("input", 0xFF202020);
            v.put("inputText", 0xFFE6E6E6);
            v.put("dialog", 0xFF202020);
            v.put("titleBar", 0xFF181818);
            v.put("titleBarText", 0xFFE6E6E6);
        } else {
            v.put("background", 0xFFF5F6F8);
            v.put("surface", 0xFFFFFFFF);
            v.put("text", 0xFF1F1F1F);
            v.put("primary", 0xFF4F8BF9);
            v.put("selection", 0xFFCFE0FD);
            v.put("selectionText", 0xFF1F1F1F);
            v.put("border", 0xFFD5D8DE);
            v.put("button", 0xFFE8EAEE);
            v.put("buttonText", 0xFF1F1F1F);
            v.put("buttonPressed", 0xFFD5D9E0);
            v.put("input", 0xFFFFFFFF);
            v.put("inputText", 0xFF1F1F1F);
            v.put("dialog", 0xFFFFFFFF);
            v.put("titleBar", 0xFFFFFFFF);
            v.put("titleBarText", 0xFF1F1F1F);
        }
        return v;
    }

    /** Admite «#RRGGBB», «#AARRGGBB» y «0xRRGGBB». Los colores inválidos se ignoran. */
    private static Integer parseColor(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) return null;
        String text = element.getAsString().trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("0x")) text = "#" + text.substring(2);
        try {
            return Color.parseColor(text);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Color inválido en tema: " + text);
            return null;
        }
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color)) / 255f;
    }

    /** Mezcla a sobre b (amount = peso de a). */
    private static int blend(int a, int b, float amount) {
        float inv = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(a) * amount + Color.red(b) * inv),
                Math.round(Color.green(a) * amount + Color.green(b) * inv),
                Math.round(Color.blue(a) * amount + Color.blue(b) * inv));
    }

    // ------------------------------------------------------------------ iconos

    /** Icono de app que aporta el tema activo (appIcons en theme.json), o {@code fallback}. */
    public static synchronized Drawable appIcon(String appId, Drawable fallback, int sizePx) {
        if (activeTheme == null || activeTheme.builtIn() || appContext == null) return fallback;
        String resource = string(activeTheme.appIcons, appId);
        if (resource == null || !resource.startsWith("resources/")) return fallback;
        Path image = activeTheme.directory.resolve(resource).normalize();
        if (!image.startsWith(activeTheme.directory) || !Files.isRegularFile(image)) return fallback;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(image.toString());
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return fallback;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true);
            return new BitmapDrawable(appContext.getResources(), scaled);
        } catch (RuntimeException | OutOfMemoryError ex) {
            return fallback;
        }
    }
}
