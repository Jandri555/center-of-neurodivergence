package com.neurocenter.core;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Image;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Catálogo y aplicación de temas integrados y temas externos .tme. */
public final class ThemeManager {

    private static final Gson GSON = new Gson();
    private static final Map<String, ThemeInfo> THEMES = new LinkedHashMap<>();
    private static ThemeInfo activeTheme;
    private static final Set<String> OVERRIDDEN_KEYS = new HashSet<>();

    public record ThemeInfo(String id, String name, String author, String version,
                            String base, Path directory, JsonObject colors, JsonObject appIcons) {
        public boolean builtIn() { return directory == null; }
        @Override public String toString() { return name; }
    }

    private ThemeManager() {}

    public static synchronized List<ThemeInfo> availableThemes() {
        refreshThemes();
        return List.copyOf(THEMES.values());
    }

    public static synchronized void refreshThemes() {
        THEMES.clear();
        addBuiltIn("light", "Claro", "light");
        addBuiltIn("dark", "Oscuro", "dark");
        Path root = AppPaths.themesDir();
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path manifest = dir.resolve("theme.json");
                if (!Files.isRegularFile(manifest)) return;
                try (Reader reader = Files.newBufferedReader(manifest)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    ThemeInfo info = parseTheme(json, dir);
                    if (info != null && !THEMES.containsKey(info.id())) THEMES.put(info.id(), info);
                } catch (Exception ex) {
                    System.err.println("Tema ignorado (" + dir.getFileName() + "): " + ex.getMessage());
                }
            });
        } catch (IOException ex) {
            System.err.println("No se pudieron leer los temas: " + ex.getMessage());
        }
    }

    private static void addBuiltIn(String id, String name, String base) {
        THEMES.put(id, new ThemeInfo(id, name, "Center of Neurodivergence", "1.0",
                base, null, new JsonObject(), new JsonObject()));
    }

    private static ThemeInfo parseTheme(JsonObject json, Path dir) {
        if (json == null) return null;
        String id = string(json, "id");
        String name = string(json, "name");
        String base = string(json, "base");
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,48}") ||
                !dir.getFileName().toString().equals(id) ||
                "light".equals(id) || "dark".equals(id) ||
                name == null || name.isBlank() ||
                !("light".equals(base) || "dark".equals(base))) return null;
        JsonObject colors = object(json, "colors");
        JsonObject icons = object(json, "appIcons");
        return new ThemeInfo(id, name, stringOr(json, "author", "Desconocido"),
                stringOr(json, "version", "1.0"), base, dir, colors, icons);
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        String value = string(obj, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static JsonObject object(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static synchronized void apply(String themeId) {
        refreshThemes();
        for (String key : OVERRIDDEN_KEYS) UIManager.getDefaults().remove(key);
        OVERRIDDEN_KEYS.clear();
        ThemeInfo theme = THEMES.get(themeId);
        if (theme == null) theme = THEMES.get("light");
        if ("dark".equals(theme.base())) FlatDarkLaf.setup();
        else FlatLightLaf.setup();

        applyColors(theme.colors());
        activeTheme = theme;
        FlatLaf.updateUI();
    }

    private static void applyColors(JsonObject colors) {
        Map<String, String> keys = Map.ofEntries(
                Map.entry("background", "Window.background"),
                Map.entry("surface", "Panel.background"),
                Map.entry("foreground", "Component.foreground"),
                Map.entry("text", "Label.foreground"),
                Map.entry("primary", "Component.focusColor"),
                Map.entry("selection", "List.selectionBackground"),
                Map.entry("selectionText", "List.selectionForeground"),
                Map.entry("border", "Component.borderColor"),
                Map.entry("button", "Button.background"),
                Map.entry("buttonText", "Button.foreground"),
                Map.entry("input", "TextField.background"),
                Map.entry("inputText", "TextField.foreground"),
                Map.entry("dialog", "Dialog.background"),
                Map.entry("dialog", "OptionPane.background"),
                Map.entry("dialogMessage", "OptionPane.messageArea.background"),
                Map.entry("dialogButtons", "OptionPane.buttonArea.background"),
                Map.entry("popup", "PopupMenu.background"),
                Map.entry("menu", "Menu.background"),
                Map.entry("menuText", "Menu.foreground"),
                Map.entry("menuItem", "MenuItem.background"),
                Map.entry("menuItemText", "MenuItem.foreground"),
                Map.entry("menuHover", "MenuItem.selectionBackground"),
                Map.entry("menuHoverText", "MenuItem.selectionForeground"),
                Map.entry("buttonHover", "Button.hoverBackground"),
                Map.entry("buttonPressed", "Button.pressedBackground"),
                Map.entry("buttonDisabled", "Button.disabledBackground"),
                Map.entry("buttonDisabledText", "Button.disabledText")
        );
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            JsonElement value = colors.get(entry.getKey());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            try {
                UIManager.put(entry.getValue(), Color.decode(value.getAsString()));
                OVERRIDDEN_KEYS.add(entry.getValue());
            } catch (NumberFormatException ignored) {
                System.err.println("Color inválido en tema: " + entry.getKey());
            }
        }
    }

    public static synchronized Icon appIcon(String appId, Icon fallback, int size) {
        if (activeTheme == null || activeTheme.builtIn()) return fallback;
        String resource = string(activeTheme.appIcons(), appId);
        if (resource == null || !resource.startsWith("resources/")) return fallback;
        Path image = activeTheme.directory().resolve(resource).normalize();
        if (!image.startsWith(activeTheme.directory()) || !Files.isRegularFile(image)) return fallback;
        ImageIcon icon = new ImageIcon(image.toString());
        if (icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) return fallback;
        Image scaled = icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    /** Compatibilidad con el arranque actual. */
    public static void apply(boolean dark) {
        apply(dark ? "dark" : "light");
    }
}
