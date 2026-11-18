package com.neurocenter.api.services;

import java.util.List;

/** API interna de Agenda que pueden consumir los plugins privilegiados. */
public interface AgendaService {
    int importExternalTasks(String source, List<ExternalTask> tasks);
}
