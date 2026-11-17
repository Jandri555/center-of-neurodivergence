package com.neurocenter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Compatibilidad declarada por una app o tema con cada variante de Center of Neurodivergence.
 *
 * Los paquetes antiguos, sin flags, se consideran compatibles solo con escritorio.
 * Cuando un paquete declara compatibilidad, debe incluir ambos booleanos explícitamente.
 */
public final class PlatformCompatibility {

    public enum Platform {
        DESKTOP("escritorio"),
        MOBILE("móvil");

        private final String label;

        Platform(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Targets(boolean desktop, boolean mobile) {
        public boolean supports(Platform platform) {
            return platform == Platform.DESKTOP ? desktop : mobile;
        }

        public void require(Platform platform, String packageDescription) throws IOException {
            if (supports(platform)) return;

            String supported = desktop && mobile ? "escritorio y móvil"
                    : desktop ? "escritorio" : mobile ? "móvil" : "ninguna plataforma";
            throw new IOException("Este dispositivo (" + platform.label()
                    + ") no es compatible con " + packageDescription
                    + ". Plataformas admitidas por el paquete: " + supported + ".");
        }
    }

    private static final Targets LEGACY_DESKTOP = new Targets(true, false);

    private PlatformCompatibility() {}

    public static Targets fromAppManifest(Manifest manifest) throws IOException {
        if (manifest == null) return LEGACY_DESKTOP;
        Attributes attributes = manifest.getMainAttributes();
        String desktop = attributes.getValue("NeuroCenter-App-Desktop");
        String mobile = attributes.getValue("NeuroCenter-App-Mobile");
        return parseFlags(desktop, mobile, "el manifiesto de la app");
    }

    public static Targets fromThemeManifest(JsonObject manifest) throws IOException {
        if (manifest == null) throw new IOException("El manifiesto del tema está vacío.");
        JsonElement compatibility = manifest.get("compatibility");
        if (compatibility == null) return LEGACY_DESKTOP;
        if (!compatibility.isJsonObject()) {
            throw new IOException("«compatibility» debe ser un objeto con los booleanos «desktop» y «mobile».");
        }

        JsonObject flags = compatibility.getAsJsonObject();
        if (!flags.has("desktop") || !flags.has("mobile")) {
            throw new IOException("La compatibilidad del tema debe declarar ambos booleanos: "
                    + "«desktop» y «mobile».");
        }
        String desktop = booleanString(flags.get("desktop"), "desktop");
        String mobile = booleanString(flags.get("mobile"), "mobile");
        return parseFlags(desktop, mobile, "el manifiesto del tema");
    }

    private static Targets parseFlags(String desktop, String mobile, String source) throws IOException {
        if (desktop == null && mobile == null) return LEGACY_DESKTOP;
        if (desktop == null || mobile == null) {
            throw new IOException("Faltan flags de compatibilidad en " + source
                    + ". Debe declarar Desktop y Mobile.");
        }

        boolean supportsDesktop = parseBoolean(desktop, "Desktop", source);
        boolean supportsMobile = parseBoolean(mobile, "Mobile", source);
        if (!supportsDesktop && !supportsMobile) {
            throw new IOException(source + " debe habilitar al menos una plataforma (Desktop o Mobile).");
        }
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
