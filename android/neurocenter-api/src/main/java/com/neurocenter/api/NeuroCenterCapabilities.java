package com.neurocenter.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Capabilities protect data and services owned by NeuroCenter.
 *
 * Android permissions (Bluetooth, camera, notifications, etc.) are separate and
 * must be declared and requested by the external package itself.
 */
public final class NeuroCenterCapabilities {
    public static final String AGENDA_READ = "agenda.read";
    public static final String AGENDA_WRITE = "agenda.write";
    public static final String CREDENTIALS_ACCESS = "credentials.access";

    private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            AGENDA_READ,
            AGENDA_WRITE,
            CREDENTIALS_ACCESS
    ));

    private NeuroCenterCapabilities() {}

    public static List<String> supported() {
        return SUPPORTED;
    }

    public static boolean isSupported(String capabilityId) {
        return capabilityId != null && SUPPORTED.contains(capabilityId);
    }
}
