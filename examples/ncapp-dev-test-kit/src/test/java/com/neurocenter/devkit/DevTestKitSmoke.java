package com.neurocenter.devkit;

/** Minimal contract tests; run with plain Java 21 (no Android or third-party libs). */
public final class DevTestKitSmoke {
    private static void has(String text, String expected) {
        if (!text.contains(expected)) throw new AssertionError("Missing: " + expected);
    }

    private static void show(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new AssertionError("Not a JSON object: " + json);
        }
        System.out.println("JSON:" + json);
    }

    public static void main(String[] args) {
        DevTestKitMobile app = new DevTestKitMobile();
        String home = app.renderJson();
        has(home, "NeuroCenter Dev Test Kit");
        show(home);

        String ping = app.onAction("ping");
        has(ping, "Respuesta JNI recibida");
        show(ping);
        String startPage = app.onAction("all");
        has(startPage, "Pruebas guiadas");
        has(startPage, "Paso 1/8");
        if (startPage.contains("Clases y Java 21: OK")) {
            throw new AssertionError("Suite unexpectedly ran all probes in one call");
        }
        show(startPage);
        for (int i = 0; i < 8; i++) {
            String result = app.onAction("suite-next");
            if (i < 7) has(result, "Paso " + (i + 2) + "/8");
            else has(result, "Informe de diagnóstico");
            show(result);
        }
        String safe = app.onAction("report");
        has(safe, "Contrato .ncapp: OK");
        has(safe, "Clases y Java 21: OK");
        has(safe, "Recursos del JAR: OK");
        has(safe, "Hilos: OK");
        has(safe, "Memoria: OK");
        has(safe, "Rendimiento: OK");
        show(app.onAction("locale"));
        has(app.onAction("report"), "Formato y configuración regional: OK");
        show(safe);
        String skipPage = app.onAction("all");
        has(skipPage, "Paso 1/7");
        has(app.onAction("suite-skip"), "Paso 2/7");
        show(app.onAction("suite-next"));

        for (String action : new String[]{"runtime", "contract", "classes", "resources", "threads", "memory",
                "speed", "sandbox", "home", "report", "agenda-menu"}) {
            show(app.onAction(action));
        }
        has(app.onAction("agenda-menu"), "Permitir siempre");
        has(app.onAction("agenda-menu"), "Agenda Bridge");
        String request = app.onAction("read-agenda");
        has(request, "\"capability\":\"agenda.read\"");
        show(request);
        show(app.onHostResponse("agenda.read", "{\"ok\":false,\"error\":\"permission denied\"}"));
        show(app.onAction("read-agenda"));
        String absentPlugin = app.onHostResponse("agenda.read",
                "{\"ok\":false,\"error\":\"Esta función requiere el plugin Agenda Bridge instalado y habilitado.\"}");
        has(absentPlugin, "SIN PLUGIN");
        show(absentPlugin);
        String confirmation = app.onAction("ask-write");
        has(confirmation, "Confirmar prueba de escritura");
        if (confirmation.contains("hostRequest")) throw new AssertionError("Write without confirmation");
        show(confirmation);
        show(app.onAction("agenda-menu"));
        show(app.onAction("ask-write"));
        String writeRequest = app.onAction("confirm-write");
        has(writeRequest, "\"capability\":\"agenda.write\"");
        show(writeRequest);
        show(app.onHostResponse("agenda.write", "{\"ok\":true,\"id\":\"test\"}"));
        show(app.onAction("report"));
        show(app.onAction("home"));
        System.out.println("PASS: Dev Test Kit app API smoke tests");
    }
}
