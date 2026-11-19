package com.neurocenter.api;

import android.content.ComponentName;
import android.content.Intent;

/**
 * Stable Android integration contract for independently installed NeuroCenter apps.
 */
public final class NeuroCenterAppContract {

    public static final String ACTION_OPEN_APP = "com.neurocenter.action.OPEN_APP";
    public static final String ACTION_BIND_BRIDGE = "com.neurocenter.action.BIND_BRIDGE";

    public static final String META_APP_ID = "com.neurocenter.app.id";
    public static final String META_APP_DESCRIPTION = "com.neurocenter.app.description";

    public static final String HOST_PACKAGE = "com.neurocenter";
    public static final String BRIDGE_SERVICE_CLASS =
            "com.neurocenter.apps.agenda.NeuroCenterBridgeService";

    private NeuroCenterAppContract() {}

    /** Explicit service intent, compatible with Android package-visibility filtering. */
    public static Intent bridgeIntent() {
        return new Intent(ACTION_BIND_BRIDGE)
                .setComponent(new ComponentName(HOST_PACKAGE, BRIDGE_SERVICE_CLASS));
    }
}
