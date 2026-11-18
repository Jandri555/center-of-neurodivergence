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
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Image;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catálogo y aplicación de temas integrados y temas externos .tme. */
public final class ThemeManager {

    private static final Gson GSON = new Gson();
    private static final Map<String, ThemeInfo> THEMES = new LinkedHashMap<>();
    private static ThemeInfo activeTheme;
    private static BufferedImage activeBackgroundImage;
    private static BufferedImage activeCenterIconImage;

    public record BackgroundInfo(Path image, int darkening, int transparency) {}

    /** Claves de FlatLaf que pueden personalizarse desde theme.json. */
    private static final Map<String, String> COLOR_UI_KEYS = Map.ofEntries(
            Map.entry("background", "Window.background"),
            Map.entry("surface", "Panel.background"),
            Map.entry("foreground", "Component.foreground"),
            Map.entry("text", "Label.foreground"),
            Map.entry("primary", "Component.accentColor"),
            Map.entry("selection", "List.selectionBackground"),
            Map.entry("selectionText", "List.selectionForeground"),
            Map.entry("border", "Component.borderColor"),
            Map.entry("focus", "Component.focusColor"),
            Map.entry("buttonFocusedBorder", "Button.focusedBorderColor"),
            Map.entry("buttonDefaultFocusedBorder", "Button.default.focusedBorderColor"),
            Map.entry("buttonDefaultFocus", "Button.default.focusColor"),
            Map.entry("buttonDefaultFocused", "Button.default.focusedBackground"),
            Map.entry("buttonDefaultFocusedText", "Button.default.focusedForeground"),
            Map.entry("button", "Button.background"),
            Map.entry("buttonText", "Button.foreground"),
            Map.entry("buttonDefault", "Button.default.background"),
            Map.entry("buttonDefaultText", "Button.default.foreground"),
            Map.entry("buttonDefaultHover", "Button.default.hoverBackground"),
            Map.entry("buttonDefaultPressed", "Button.default.pressedBackground"),
            Map.entry("input", "TextField.background"),
            Map.entry("inputText", "TextField.foreground"),
            Map.entry("dialog", "Dialog.background"),
            Map.entry("dialogOptionPane", "OptionPane.background"),
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
            Map.entry("buttonTabUnderline", "ToggleButton.tab.underlineColor"),
            Map.entry("buttonTabSelectedText", "ToggleButton.tab.selectedForeground"),
            Map.entry("buttonTabSelectedBackground", "ToggleButton.tab.selectedBackground"),
            Map.entry("buttonTabHover", "ToggleButton.tab.hoverBackground"),
            Map.entry("buttonDisabledText", "Button.disabledText"),
            Map.entry("titleBar", "TitlePane.background"),
            Map.entry("titleBarText", "TitlePane.foreground"),
            Map.entry("titleBarInactive", "TitlePane.inactiveBackground"),
            Map.entry("titleBarInactiveText", "TitlePane.inactiveForeground"),
            Map.entry("titleBarButton", "TitlePane.buttonBackground"),
            Map.entry("titleBarButtonText", "TitlePane.buttonForeground"),
            Map.entry("titleBarButtonHover", "TitlePane.buttonHoverBackground"),
            Map.entry("titleBarButtonPressed", "TitlePane.buttonPressedBackground")
    );

    public record ThemeInfo(String id, String name, String author, String version,
                            String base, Path directory, JsonObject colors, JsonObject appIcons,
                            Path centerIcon, BackgroundInfo background,
                            PlatformCompatibility.Targets compatibility) {
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
        // Los temas integrados tienen una variante equivalente en ambas plataformas.
        JsonObject colors = new JsonObject();
        if ("dark".equals(base)) {
            // Oscuro usa carbón profundo, pero conserva separación entre superficies.
            // Negro OLED sigue siendo un tema externo independiente con negro puro.
            colors.addProperty("background", "#181818");
            colors.addProperty("surface", "#202020");
            colors.addProperty("button", "#292929");
            colors.addProperty("buttonHover", "#333333");
            colors.addProperty("buttonPressed", "#3A3A3A");
            colors.addProperty("input", "#202020");
            colors.addProperty("dialog", "#202020");
            colors.addProperty("dialogOptionPane", "#202020");
            colors.addProperty("dialogMessage", "#202020");
            colors.addProperty("dialogButtons", "#202020");
            colors.addProperty("popup", "#202020");
            colors.addProperty("menu", "#202020");
            colors.addProperty("menuItem", "#202020");
            colors.addProperty("menuHover", "#333333");
            colors.addProperty("titleBar", "#181818");
        }
        THEMES.put(id, new ThemeInfo(id, name, "Center of Neurodivergence", "1.0",
                base, null, colors, new JsonObject(), null, null,
                new PlatformCompatibility.Targets(true, true)));
    }

    private static ThemeInfo parseTheme(JsonObject json, Path dir) throws IOException {
        if (json == null) return null;
        String id = string(json, "id");
        String name = string(json, "name");
        String base = string(json, "base");
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,48}") ||
                !dir.getFileName().toString().equals(id) ||
                "light".equals(id) || "dark".equals(id) ||
                name == null || name.isBlank() ||
                !("light".equals(base) || "dark".equals(base))) return null;
        validateAssets(dir, json);
        PlatformCompatibility.Targets compatibility = PlatformCompatibility.fromThemeManifest(json);
        JsonObject colors = object(json, "colors");
        JsonObject icons = object(json, "appIcons");

        JsonObject backgroundJson = object(json, "background");
        String backgroundResource = string(backgroundJson, "image");
        Path backgroundImage = backgroundResource == null ? null
                : resolveImageResource(dir, backgroundResource, "background.image");
        BackgroundInfo background = backgroundImage == null ? null : new BackgroundInfo(
                backgroundImage,
                percentage(backgroundJson, "darkening", 0),
                percentage(backgroundJson, "transparency", 0));

        String centerIconResource = string(json, "centerIcon");
        Path centerIcon = centerIconResource == null ? null
                : resolveImageResource(dir, centerIconResource, "centerIcon");

        return new ThemeInfo(id, name, stringOr(json, "author", "Desconocido"),
                stringOr(json, "version", "1.0"), base, dir, colors, icons,
                centerIcon, background, compatibility);
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
        ThemeInfo theme = THEMES.get(themeId);
        if (theme == null) theme = THEMES.get("light");
        if (!theme.compatibility().supports(com.neurocenter.api.NeuroPlatform.DESKTOP)) {
            try {
                theme.compatibility().require(com.neurocenter.api.NeuroPlatform.DESKTOP,
                        "el tema «" + theme.name() + "»");
            } catch (IOException ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        }

        // Los colores de cada tema deben vivir en los defaults del Look and Feel,
        // no en los defaults globales de UIManager. Así, al cambiar a otro tema,
        // FlatLaf reconstruye los valores y no arrastra colores del tema anterior.
        FlatLaf.setGlobalExtraDefaults(createThemeDefaults(theme.colors()));

        // Se sincroniza de nuevo tras instalar el nuevo Look and Feel para evitar
        // que el color de la barra de título conserve el fondo del tema anterior.
        UIManager.getDefaults().remove("RootPane.background");

        if ("dark".equals(theme.base())) FlatDarkLaf.setup();
        else FlatLightLaf.setup();

        // La barra de título debe compartir el fondo de la ventana.
        // Así respeta también los temas OLED con fondo negro puro.
        UIManager.put("TitlePane.unifiedBackground", true);

        // Los botones predeterminados de JOptionPane no deben destacar en azul
        // solo por ser la primera opción del diálogo.
        Color normalButton = UIManager.getColor("Button.background");
        Color normalButtonText = UIManager.getColor("Button.foreground");
        if (normalButton != null) UIManager.put("Button.default.background", normalButton);
        if (normalButtonText != null) UIManager.put("Button.default.foreground", normalButtonText);
        UIManager.put("Button.default.boldText", false);

        // El foco debe mantener un borde neutro y no confundirse con el acento.
        Color neutralFocus = UIManager.getColor("Component.borderColor");
        if (neutralFocus != null) {
            UIManager.put("Component.focusColor", neutralFocus);
            UIManager.put("Button.default.focusColor", neutralFocus);
            UIManager.put("Button.default.focusedBorderColor", neutralFocus);
            UIManager.put("Button.focusedBorderColor", neutralFocus);
        }

        Color windowBackground = UIManager.getColor("Window.background");
        if (windowBackground != null) UIManager.put("RootPane.background", windowBackground);

        activeTheme = theme;
        activeBackgroundImage = theme.background() == null
                ? null : readImageOrNull(theme.background().image(), "imagen de fondo");
        activeCenterIconImage = theme.centerIcon() == null
                ? null : readImageOrNull(theme.centerIcon(), "icono de NeuroCenter");
        FlatLaf.updateUI();
    }

    /**
     * Convierte los colores del manifiesto en defaults propios del Look and Feel.
     * FlatLaf consume estos valores al instalar cada variante clara u oscura.
     */
    private static Map<String, String> createThemeDefaults(JsonObject colors) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : COLOR_UI_KEYS.entrySet()) {
            JsonElement value = colors.get(entry.getKey());
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) continue;
            try {
                Color color = Color.decode(value.getAsString().trim());
                String normalized = String.format(java.util.Locale.ROOT, "#%06x",
                        color.getRGB() & 0xFFFFFF);
                defaults.put(entry.getValue(), normalized);
                if ("Window.background".equals(entry.getValue())) {
                    defaults.put("RootPane.background", normalized);
                }
            } catch (NumberFormatException ignored) {
                System.err.println("Color inválido en tema: " + entry.getKey());
            }
        }
        return defaults;
    }

    /**
     * Valida todos los recursos gráficos referenciados por el manifiesto.
     * Las rutas se limitan a resources/ y las imágenes deben ser decodificables.
     */
    public static void validateAssets(Path directory, JsonObject manifest) throws IOException {
        if (manifest == null) throw new IOException("El manifiesto del tema está vacío.");

        if (manifest.has("background") && !manifest.get("background").isJsonObject()) {
            throw new IOException("El campo «background» debe ser un objeto.");
        }
        JsonObject background = object(manifest, "background");
        String backgroundImage = string(background, "image");
        if (background.has("image") && backgroundImage == null) {
            throw new IOException("«background.image» debe ser una ruta de imagen.");
        }
        if (backgroundImage != null) {
            resolveImageResource(directory, backgroundImage, "background.image");
            percentage(background, "darkening", 0);
            percentage(background, "transparency", 0);
        }

        String centerIcon = string(manifest, "centerIcon");
        if (manifest.has("centerIcon") && centerIcon == null) {
            throw new IOException("«centerIcon» debe ser una ruta de imagen PNG.");
        }
        if (centerIcon != null) resolveImageResource(directory, centerIcon, "centerIcon");

        JsonObject appIcons = object(manifest, "appIcons");
        for (Map.Entry<String, JsonElement> entry : appIcons.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IOException("El icono de «" + entry.getKey() + "» debe ser una ruta de imagen.");
            }
            resolveImageResource(directory, entry.getValue().getAsString(), "appIcons." + entry.getKey());
        }
    }

    private static Path resolveImageResource(Path directory, String resource, String field) throws IOException {
        if (resource == null || !resource.startsWith("resources/")) {
            throw new IOException("«" + field + "» debe apuntar a un archivo dentro de resources/.");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resourcesRoot = root.resolve("resources").normalize();
        Path image = root.resolve(resource).normalize();
        if (!image.startsWith(resourcesRoot) || !Files.isRegularFile(image)) {
            throw new IOException("No se encuentra el recurso indicado por «" + field + "» dentro de resources/.");
        }
        if (("background.image".equals(field) || "centerIcon".equals(field))
                && !resource.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            throw new IOException("«" + field + "» debe ser un archivo PNG.");
        }
        readImage(image, field);
        return image;
    }

    private static int percentage(JsonObject object, String key, int fallback) throws IOException {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("«" + key + "» debe ser un número entre 0 y 100.");
        }
        int result;
        try {
            result = value.getAsInt();
        } catch (RuntimeException ex) {
            throw new IOException("«" + key + "» debe ser un número entero entre 0 y 100.", ex);
        }
        if (result < 0 || result > 100) {
            throw new IOException("«" + key + "» debe estar entre 0 y 100.");
        }
        return result;
    }

    private static BufferedImage readImageOrNull(Path image, String description) {
        try {
            return readImage(image, description);
        } catch (IOException ex) {
            System.err.println("No se pudo cargar " + description + ": " + ex.getMessage());
            return null;
        }
    }

    private static BufferedImage readImage(Path image, String description) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) throw new IOException("No se pudo leer " + description + ".");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Formato de imagen no válido en " + description + ".");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 16384 || height > 16384
                        || (long) width * height > 40_000_000L) {
                    throw new IOException("Las dimensiones de " + description + " son demasiado grandes.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IOException("No se pudo decodificar " + description + ".");
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Pinta el fondo del tema ocupando el área disponible, sin deformar la imagen. */
    public static synchronized void paintBackground(Graphics2D graphics, int width, int height) {
        if (width <= 0 || height <= 0) return;
        Color base = UIManager.getColor("Window.background");
        if (base == null) base = Color.DARK_GRAY;
        graphics.setColor(base);
        graphics.fillRect(0, 0, width, height);

        if (activeBackgroundImage == null || activeTheme == null || activeTheme.background() == null) return;

        BufferedImage image = activeBackgroundImage;
        double scale = Math.max(width / (double) image.getWidth(), height / (double) image.getHeight());
        int drawWidth = Math.max(width, (int) Math.ceil(image.getWidth() * scale));
        int drawHeight = Math.max(height, (int) Math.ceil(image.getHeight() * scale));
        int x = (width - drawWidth) / 2;
        int y = (height - drawHeight) / 2;

        var oldComposite = graphics.getComposite();
        var oldHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        float imageOpacity = 1f - activeTheme.background().transparency() / 100f;
        graphics.setComposite(AlphaComposite.SrcOver.derive(imageOpacity));
        graphics.drawImage(image, x, y, drawWidth, drawHeight, null);
        graphics.setComposite(AlphaComposite.SrcOver);

        int darkness = activeTheme.background().darkening();
        if (darkness > 0) {
            graphics.setColor(new Color(0, 0, 0, Math.round(darkness * 255f / 100f)));
            graphics.fillRect(0, 0, width, height);
        }
        graphics.setComposite(oldComposite);
        if (oldHint != null) graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint);
    }

    public static synchronized Image centerIconImage(int size) {
        if (activeCenterIconImage == null || size < 1) return null;
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale = Math.min(size / (double) activeCenterIconImage.getWidth(),
                    size / (double) activeCenterIconImage.getHeight());
            int width = Math.max(1, (int) Math.round(activeCenterIconImage.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(activeCenterIconImage.getHeight() * scale));
            graphics.drawImage(activeCenterIconImage,
                    (size - width) / 2, (size - height) / 2, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    public static synchronized Path centerIconPath() {
        return activeTheme == null ? null : activeTheme.centerIcon();
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
