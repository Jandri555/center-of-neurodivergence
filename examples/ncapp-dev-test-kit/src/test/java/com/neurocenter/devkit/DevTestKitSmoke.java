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

        String safe = app.onAction("all");
        has(safe, "Informe de diagnóstico");
        has(safe, "Clases y Java 21: OK");
        has(safe, "Recursos del JAR: OK");
        has(safe, "Hilos: OK");
        has(safe, "Memoria: OK");
        show(safe);

        for (String action : new String[]{"runtime", "classes", "resources", "threads", "memory",
                "speed", "sandbox", "home", "report", "agenda-menu"}) {
            show(app.onAction(action));
        }
        String request = app.onAction("read-agenda");
        has(request, "\"capability\":\"agenda.read\"");
        show(request);
        show(app.onHostResponse("agenda.read", "{\"ok\":false,\"error\":\"permission denied\"}"));
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
