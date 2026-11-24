package com.neurocenter.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

import com.neurocenter.api.NeuroCenterAppContract;
import com.neurocenter.api.NeuroCenterCapabilities;

/**
 * Host-rendered permission prompt for independently installed APK clients.
 * The caller is obtained from Android startActivityForResult identity, never
 * from package names or UIDs provided through intent extras.
 */
public final class AndroidPermissionRequestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        String caller = getCallingPackage();
        String permission = getIntent() == null ? null :
                getIntent().getStringExtra(NeuroCenterAppContract.EXTRA_REQUESTED_PERMISSION);
        if (caller == null || permission == null || !NeuroCenterCapabilities.isSupported(permission)
                || !AndroidExternalApps.declaredCapabilities(this, caller).contains(permission)) {
            finish();
            return;
        }
        String signer = AndroidExternalApps.signingDigest(this, caller);
        if (signer == null) { finish(); return; }
        try (NeuroCenterCapabilityStore store = new NeuroCenterCapabilityStore(this)) {
            Boolean remembered = store.storedDecision(caller, signer, permission);
            if (remembered != null) {
                setResult(remembered ? RESULT_OK : RESULT_CANCELED);
                finish();
                return;
            }
        } catch (Exception ex) {
            finish();
            return;
        }

        CharSequence label;
        try {
            label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(caller, 0));
        } catch (Exception ex) { label = caller; }
        String name = label.toString();
        String[] options = {"Permitir", "Denegar", "Permitir siempre", "Denegar siempre"};
        new AlertDialog.Builder(this)
                .setTitle("Permiso solicitado por " + name)
                .setMessage("La aplicación «" + name + "» solicita:\n\n" + permission
                        + "\n\nEl acceso está limitado a la capacidad declarada en su AndroidManifest.xml.")
                .setItems(options, (dialog, selected) -> {
                    try (NeuroCenterCapabilityStore store = new NeuroCenterCapabilityStore(this)) {
                        if (selected == 0) {
                            NeuroCenterCapabilityStore.grantOnce(caller, signer, permission);
                            setResult(RESULT_OK);
                        } else if (selected == 2 || selected == 3) {
                            store.setDecision(caller, signer, permission, selected == 2);
                            setResult(selected == 2 ? RESULT_OK : RESULT_CANCELED);
                        }
                    } catch (Exception ignored) {
                        setResult(RESULT_CANCELED);
                    }
                    finish();
                })
                .setOnCancelListener(ignored -> finish()).show();
    }
}
