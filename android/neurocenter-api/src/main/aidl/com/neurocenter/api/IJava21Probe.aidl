package com.neurocenter.api;

/** Diagnóstico de la JVM, no acepta ni ejecuta bytecode externo. */
interface IJava21Probe {
    String probeVersion();
}
