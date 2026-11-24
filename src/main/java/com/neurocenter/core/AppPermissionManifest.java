package com.neurocenter.core;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.Manifest;

/** Strict manifest allowlist; missing attribute means no permissions. */
public final class AppPermissionManifest {
    public static final String ATTRIBUTE = "NeuroCenter-App-Permissions";
    private AppPermissionManifest() {}

    public static Set<String> parse(Manifest manifest) throws IOException {
        if (manifest == null) return Set.of();
        String raw = manifest.getMainAttributes().getValue(ATTRIBUTE);
        if (raw == null || raw.isBlank()) return Set.of();
        if (raw.length() > 2048) throw new IOException("Manifest permission list is too long");
        Set<String> result = new LinkedHashSet<>();
        for (String value : raw.split(",", -1)) {
            String id = value.trim();
            if (!NeuroCenterSecurity.isSupportedPermission(id))
                throw new IOException("Permiso no reconocido en el manifiesto: " + id);
            if (!result.add(id)) throw new IOException("Permiso duplicado: " + id);
        }
        return Collections.unmodifiableSet(result);
    }

    public static Set<String> deserialize(String raw) {
        return raw == null || raw.isBlank() ? Set.of() : Set.of(raw.split(","));
    }

    public static String serialize(Set<String> ids) { return String.join(",", ids); }
}
