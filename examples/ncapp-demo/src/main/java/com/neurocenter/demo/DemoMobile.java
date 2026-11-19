package com.neurocenter.demo;

/** Punto de entrada móvil: no importa Android, Swing ni ART. */
public final class DemoMobile {
    private final Counter counter = new Counter();

    public String renderJson() {
        return "{\"title\":\"NeuroCenter Java 21\","
                + "\"text\":\"Esta interfaz la genera una .ncapp Java 21. Contador: "
                + counter.value()
                + "\",\"actions\":[{\"id\":\"sumar\",\"label\":\"Sumar uno\"}]}";
    }

    public String onAction(String actionId) {
        if ("sumar".equals(actionId)) counter.increment();
        return renderJson();
    }
}
