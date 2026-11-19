package com.neurocenter.demo;

/** Una clase Java 21 sin dependencia de Android ni de Swing. */
public final class DemoMobile {
    private final Counter counter = new Counter();
    private String status = "Esta interfaz la genera una .ncapp Java 21.";

    public String renderJson() {
        return "{\"title\":\"NeuroCenter Java 21\","
                + "\"text\":\"" + status + " Contador: " + counter.value() + "\","
                + "\"actions\":["
                + "{\"id\":\"sumar\",\"label\":\"Sumar uno\"},"
                + "{\"id\":\"agenda\",\"label\":\"Probar permiso de Agenda\"}]}";
    }

    public String onAction(String actionId) {
        if ("sumar".equals(actionId)) counter.increment();
        if ("agenda".equals(actionId)) {
            return "{\"hostRequest\":{\"capability\":\"agenda.read\",\"operation\":\"list\"}}";
        }
        return renderJson();
    }

    /** La Agenda nunca se lee directamente: NeuroCenter valida el permiso. */
    public String onHostResponse(String capability, String responseJson) {
        if ("agenda.read".equals(capability) && responseJson.contains("\"ok\":true")) {
            status = "Agenda autorizada por NeuroCenter.";
        } else {
            status = "Agenda bloqueada por el sistema de permisos.";
        }
        return renderJson();
    }
}
