package com.neurocenter.core;

import android.os.Bundle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neurocenter.api.NeuroPlatform;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Compatibilidad declarada por una app o tema con cada variante de Center of Neurodivergence.
 * Versión Android de la clase de escritorio: misma semántica y mismos mensajes.
 *
 * Cada flag se interpreta de forma independiente: si falta, su valor es false.
 * Un paquete debe habilitar al menos una plataforma para poder instalarse o aplicarse.
 */
public final class PlatformCompatibility {

    public static final String APP_DESKTOP = "NeuroCenter-App-Desktop";
    public static final String APP_MOBILE = "NeuroCenter-App-Mobile";

    public static final class Targets {
        private final boolean desktop;
        private final boolean mobile;

        public Targets(boolean desktop, boolean mobile) {
            this.desktop = desktop;
            this.mobile = mobile;
        }

        public boolean desktop() { return desktop; }

        public boolean mobile() { return mobile; }

        public boolean supports(NeuroPlatform platform) {
            return platform == NeuroPlatform.DESKTOP ? desktop : mobile;
        }

        public void require(NeuroPlatform platform, String packageDescription) throws IOException {
            if (supports(platform)) return;

            String supported = desktop && mobile ? "escritorio y móvil"
                    : desktop ? "escritorio" : mobile ? "móvil" : "ninguna plataforma";
            throw new IOException("Este dispositivo (" + platform.label()
                    + ") no es compatible con " + packageDescription
                    + ". Plataformas admitidas por el paquete: " + supported + ".");
        }
    }

    private static final Targets NO_PLATFORMS = new Targets(false, false);

    private PlatformCompatibility() {}

    /** Flags en el manifiesto de un JAR (META-INF/MANIFEST.MF). */
    public static Targets fromJarManifest(Manifest manifest) throws IOException {
        if (manifest == null) return NO_PLATFORMS;
        Attributes attributes = manifest.getMainAttributes();
        return parseFlags(attributes.getValue(APP_DESKTOP), attributes.getValue(APP_MOBILE),
                "el manifiesto de la app");
    }

    /**
     * Flags en los {@code <meta-data>} del {@code <application>} de un APK.
     * aapt convierte "true"/"false" en booleanos; también se admiten cadenas.
     */
    public static Targets fromApkMetaData(Bundle metaData) throws IOException {
        if (metaData == null) return NO_PLATFORMS;
        return parseFlags(metaFlag(metaData, APP_DESKTOP), metaFlag(metaData, APP_MOBILE),
                "el AndroidManifest de la app");
    }

    @SuppressWarnings("deprecation")
    private static String metaFlag(Bundle metaData, String key) throws IOException {
        if (!metaData.containsKey(key)) return null;
        Object value = metaData.get(key);
        if (value instanceof Boolean || value instanceof String) return String.valueOf(value);
        throw new IOException("El meta-data «" + key + "» debe ser «true» o «false».");
    }

    public static Targets fromThemeManifest(JsonObject manifest) throws IOException {
        if (manifest == null) throw new IOException("El manifiesto del tema está vacío.");
        JsonElement compatibility = manifest.get("compatibility");
        if (compatibility == null) return NO_PLATFORMS;
        if (!compatibility.isJsonObject()) {
            throw new IOException("«compatibility» debe ser un objeto con los booleanos «desktop» y «mobile».");
        }

        JsonObject flags = compatibility.getAsJsonObject();
        String desktop = flags.has("desktop") ? booleanString(flags.get("desktop"), "desktop") : null;
        String mobile = flags.has("mobile") ? booleanString(flags.get("mobile"), "mobile") : null;
        return parseFlags(desktop, mobile, "el manifiesto del tema");
    }

    private static Targets parseFlags(String desktop, String mobile, String source) throws IOException {
        // Un flag ausente equivale siempre a false, nunca a compatibilidad heredada.
        boolean supportsDesktop = desktop != null && parseBoolean(desktop, "Desktop", source);
        boolean supportsMobile = mobile != null && parseBoolean(mobile, "Mobile", source);
        return new Targets(supportsDesktop, supportsMobile);
    }

    private static boolean parseBoolean(String value, String flag, String source) throws IOException {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IOException("El flag " + flag + " de " + source + " debe ser «true» o «false».");
    }

    private static String booleanString(JsonElement value, String flag) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("El flag «" + flag + "» del tema debe ser booleano (true/false).");
        }
        return value.getAsString();
    }
}
