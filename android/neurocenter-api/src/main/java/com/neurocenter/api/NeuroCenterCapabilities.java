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
    public static final String NETWORK_CONNECT = "network.connect";
    public static final String NETWORK_LISTEN = "network.listen";
    public static final String FILES_READ_DOCUMENTS = "files.read.documents";
    public static final String FILES_WRITE_DOCUMENTS = "files.write.documents";
    public static final String CLIPBOARD_ACCESS = "clipboard.access";

    private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            AGENDA_READ,
            AGENDA_WRITE,
            CREDENTIALS_ACCESS,
            NETWORK_CONNECT,
            NETWORK_LISTEN,
            FILES_READ_DOCUMENTS,
            FILES_WRITE_DOCUMENTS,
            CLIPBOARD_ACCESS
    ));

    private NeuroCenterCapabilities() {}

    public static List<String> supported() {
        return SUPPORTED;
    }

    public static boolean isSupported(String capabilityId) {
        return capabilityId != null && SUPPORTED.contains(capabilityId);
    }
}
