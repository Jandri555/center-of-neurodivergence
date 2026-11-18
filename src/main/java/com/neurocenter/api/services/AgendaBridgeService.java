package com.neurocenter.api.services;

import java.util.List;

/** Servicio estable que una aplicación externa utiliza para importar actividades en Agenda. */
public interface AgendaBridgeService {
    int importTasks(String source, List<ExternalTask> tasks);
}
