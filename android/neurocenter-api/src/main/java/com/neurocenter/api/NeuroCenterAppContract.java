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
    public static final String META_APP_PERMISSIONS = "com.neurocenter.app.permissions";
    public static final String ACTION_REQUEST_PERMISSION = "com.neurocenter.action.REQUEST_PERMISSION";
    public static final String EXTRA_REQUESTED_PERMISSION = "com.neurocenter.extra.PERMISSION";

    public static final String HOST_PACKAGE = "com.neurocenter";
    public static final String BRIDGE_SERVICE_CLASS =
            "com.neurocenter.apps.agenda.NeuroCenterBridgeService";

    private NeuroCenterAppContract() { }

    /** Call startActivityForResult(intent, code) from the requesting Android app. */
    public static Intent permissionRequestIntent(String permission) {
        return new Intent(ACTION_REQUEST_PERMISSION)
                .setComponent(new ComponentName(HOST_PACKAGE,
                        "com.neurocenter.core.AndroidPermissionRequestActivity"))
                .putExtra(EXTRA_REQUESTED_PERMISSION, permission);
    }

    /** Explicit service intent, compatible with Android package-visibility filtering. */
    public static Intent bridgeIntent() {
        return new Intent(ACTION_BIND_BRIDGE)
                .setComponent(new ComponentName(HOST_PACKAGE, BRIDGE_SERVICE_CLASS));
    }
}
