package com.neurocenter.apps.agenda;

import android.content.Context;

import com.neurocenter.api.NeuroCenterCapabilities;
import com.neurocenter.core.NcappManager;
import com.neurocenter.core.NcappPermissionStore;
import com.neurocenter.core.AndroidPluginServices;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Puente de capacidades para ncapp. Toda operación se valida en el proceso
 * anfitrión; ningún descriptor de AgendaStore se entrega al UID aislado.
 */
public final class NcappHostServices {
    private NcappHostServices() {}

    public static String handle(Context context, NcappManager.InstalledApp app,
                                String request) {
        try {
            if (!NcappManager.inspect(app.archive()).sha256().equals(app.sha256())) {
                throw new SecurityException("La aplicación instalada ha sido modificada.");
            }
            if (request == null || request.length() > 4096) {
                throw new IllegalArgumentException("Solicitud demasiado larga.");
            }
            JSONObject req = new JSONObject(request);
            String capability = req.getString("capability");
            String operation = req.getString("operation");
            if (!NeuroCenterCapabilities.isSupported(capability)) {
                throw new SecurityException("Capacidad desconocida.");
            }
            // La concesión se liga tanto al ID como al SHA-256 actual del paquete.
            if (!NcappPermissionStore.has(context, app, capability)) {
                throw new SecurityException("Permiso «" + capability + "» denegado por NeuroCenter.");
            }
            AndroidPluginServices.AgendaBridge agenda = AndroidPluginServices.requireAgendaBridge();
            if (NeuroCenterCapabilities.AGENDA_READ.equals(capability)
                    && "list".equals(operation)) {
                String entries = agenda.getAgendaJson();
                if (entries.length() > 45000) {
                    throw new IllegalStateException("Demasiadas entradas para la API inicial.");
                }
                return "{\"ok\":true,\"entries\":" + entries + "}";
            }
            if (NeuroCenterCapabilities.AGENDA_WRITE.equals(capability)
                    && "addTask".equals(operation)) {
                String title = req.getString("title").trim();
                String notes = req.optString("notes", "");
                String date = req.optString("date", "").trim();
                String time = req.optString("time", "").trim();
                if (title.isEmpty() || title.length() > 512 || notes.length() > 8192) {
                    throw new IllegalArgumentException("Título o notas inválidos.");
                }
                if (!date.isEmpty()) LocalDate.parse(date);
                if (!time.isEmpty()) LocalTime.parse(time);
                String taskId = agenda.addTask(title, date.isEmpty() ? null : date,
                        time.isEmpty() ? null : time, notes);
                return new JSONObject().put("ok", true).put("id", taskId).toString();
            }
            throw new UnsupportedOperationException("Operación no implementada: "
                    + capability + "/" + operation);
        } catch (Exception ex) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", ex.getMessage() == null ? "Error" : ex.getMessage())
                        .toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"Error del puente\"}";
            }
        }
    }
}
