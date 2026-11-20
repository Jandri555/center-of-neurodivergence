package com.neurocenter.devkit;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone NeuroCenter Java 21 .ncapp diagnostic app for Android.
 * Never requires Android APIs. Host only draws JSON; all Java runs in the
 * isolated HotSpot process. No automatic network calls or host writes.
 */
public final class DevTestKitMobile {
    private static final String VERSION = "1.2.0";
    private static final int MAX_REPORT_CHARS = 7400;
    private static final String SAMPLE = "neurocenter-dev-test-kit/1.0\n";
    private static final String[] SAFE_TESTS = {
        "runtime", "classes", "resources", "threads", "memory", "speed", "sandbox"
    };
    private static final Map<String, String> LABELS = Map.of(
        "runtime", "Entorno Java", "classes", "Clases y Java 21",
        "resources", "Recursos del JAR", "threads", "Hilos",
        "memory", "Memoria", "speed", "Rendimiento",
        "sandbox", "Aislamiento", "network", "Red local",
        "agenda", "Agenda (lectura)"
    );

    private record Result(String state, String message) { }
    private record ProbeRecord(String text, int number) { }
    private final Map<String, Result> results = new LinkedHashMap<>();
    private String page = "home";
    private String pendingHost = "";
    private String lastInfo = "Todavía no se han ejecutado pruebas.";
    private int suiteIndex = -1;

    public DevTestKitMobile() { }

    public String renderJson() {
        return switch (page) {
            case "results" -> resultsPage();
            case "suite" -> suitePage();
            case "write-confirm" -> screen("Confirmar prueba de escritura",
                "Esta prueba AÑADE una tarea real a la Agenda de NeuroCenter, "
                    + "si el permiso agenda.write está concedido. "
                    + "Tendrás que borrarla manualmente. No se ejecuta automáticamente.",
                buttons("confirm-write", "Crear tarea de prueba", "agenda-menu", "Cancelar"));
            case "agenda-menu" -> screen("Pruebas de Agenda",
                "La aplicación solicita operaciones al anfitrión. "
                    + "NeuroCenter debe comprobar los permisos de la .ncapp.\n\n"
                    + lastInfo + "\n\n" + resultLine("agenda"),
                buttons("read-agenda", "Probar agenda.read", "ask-write", "Probar agenda.write",
                    "home", "Inicio"));
            case "single" -> screen("Resultado de prueba", lastInfo,
                buttons("report", "Ver informe", "all", "Repetir pruebas seguras",
                    "home", "Inicio"));
            default -> homePage();
        };
    }

    public String onAction(String actionId) {
        if (actionId == null) return renderJson();
        try {
            switch (actionId) {
                case "home" -> page = "home";
                case "ping" -> {
                    // No diagnostic writes, file I/O, threads or probes.
                    // Tests the ART -> JNI -> HotSpot -> JNI -> ART path.
                    lastInfo = "Respuesta JNI recibida. onAction(ping) ha terminado. "
                        + "Si ves este mensaje, la comunicación básica funciona.";
                    page = "single";
                }
                case "report" -> page = "results";
                case "agenda-menu" -> page = "agenda-menu";
                case "ask-write" -> page = "write-confirm";
                case "confirm-write" -> {
                    page = "agenda-menu";
                    pendingHost = "write";
                    return "{\"hostRequest\":{\"capability\":\"agenda.write\",\"operation\":\"addTask\","
                            + "\"title\":\"[Dev Test Kit] Tarea de prueba\","
                            + "\"notes\":\"Generada a petición del usuario por NeuroCenter Dev Test Kit. Borrado manual.\"}}";
                }
                case "read-agenda" -> {
                    page = "agenda-menu";
                    pendingHost = "read";
                    return "{\"hostRequest\":{\"capability\":\"agenda.read\",\"operation\":\"list\"}}";
                }
                case "all" -> {
                    // Never execute all probes in one native JVM call. A fatal
                    // SIGABRT cannot be caught in Java, so each probe needs its
                    // own user-triggered action and visible last-known stage.
                    suiteIndex = 0;
                    lastInfo = "Elige «Ejecutar» para probar una función cada vez. "
                        + "Si Android aborta, el informe de NeuroCenter muestra "
                        + "la última acción iniciada.";
                    page = "suite";
                }
                case "suite-next" -> {
                    if (suiteIndex >= 0 && suiteIndex < SAFE_TESTS.length) {
                        String active = SAFE_TESTS[suiteIndex];
                        runTest(active);
                        suiteIndex++;
                    }
                    page = suiteIndex >= SAFE_TESTS.length ? "results" : "suite";
                }
                case "suite-skip" -> {
                    if (suiteIndex >= 0 && suiteIndex < SAFE_TESTS.length) {
                        String active = SAFE_TESTS[suiteIndex++];
                        results.put(active, new Result("OMITIDO",
                            "Prueba omitida por el usuario (sin ejecutar)."));
                        lastInfo = resultLine(active);
                    }
                    page = suiteIndex >= SAFE_TESTS.length ? "results" : "suite";
                }
                case "runtime", "classes", "resources", "threads", "memory",
                     "speed", "sandbox", "network" -> {
                    runTest(actionId);
                    page = "single";
                }
                default -> {
                    lastInfo = "Acción desconocida: " + actionId;
                    page = "single";
                }
            }
        } catch (Throwable problem) {
            lastInfo = "ERROR durante la acción: " + safeError(problem);
            page = "single";
        }
        return renderJson();
    }

    /** Called only with NeuroCenter-verified responses; does not echo Agenda entries. */
    public String onHostResponse(String capability, String responseJson) {
        String type = "agenda.read".equals(capability) ? "read"
                : "agenda.write".equals(capability) ? "write" : "unknown";
        if (!type.equals(pendingHost)) {
            lastInfo = "AVISO: respuesta del anfitrión inesperada.";
            page = "agenda-menu";
            return renderJson();
        }
        pendingHost = "";
        boolean ok = responseJson != null && responseJson.trim().startsWith("{\"ok\":true");
        String note = ok
                ? ("read".equals(type)
                    ? "NeuroCenter autorizó agenda.read. Se recibió una respuesta, sin mostrar tareas privadas."
                    : "NeuroCenter creó la tarea de prueba. Bórrala cuando termines.")
                : "NeuroCenter denegó la operación o devolvió un error. "
                    + "Comprueba los permisos en el gestor de aplicaciones.";
        results.put("agenda", new Result(ok ? "OK" : "DENEGADO/ERROR",
                capability + ": " + note));
        lastInfo = note;
        page = "agenda-menu";
        return renderJson();
    }

    private String homePage() {
        return screen("NeuroCenter Dev Test Kit",
            "Versión " + VERSION + " | Java 21 | Pruebas para .ncapp Android\n\n"
                + "Pruebas guiadas: una prueba por pulsación para aislar cierres nativos. "
                + "No cambian la Agenda ni realizan conexiones de red. "
                + "Las pruebas de red y escritura son manuales.\n\n"
                + "Completadas: " + results.size() + " | " + shortSummary(),
            buttons("ping", "Probar comunicación JNI", "all", "Pruebas guiadas (paso a paso)",
                "runtime", "Java y VM",
                "classes", "Clases y Java 21", "resources", "Recursos JAR",
                "threads", "Hilos", "memory", "Memoria", "speed", "Rendimiento",
                "sandbox", "Aislamiento", "network", "Red local (manual)",
                "agenda-menu", "Permisos de Agenda", "report", "Informe"));
    }

    /** Guided mode: the next probe is never started automatically. */
    private String suitePage() {
        if (suiteIndex < 0 || suiteIndex >= SAFE_TESTS.length) return resultsPage();
        String active = SAFE_TESTS[suiteIndex];
        String progress = "Paso " + (suiteIndex + 1) + "/" + SAFE_TESTS.length
                + ": " + LABELS.getOrDefault(active, active);
        String last = suiteIndex == 0 ? "Ninguna prueba ejecutada todavía."
                : "Último resultado:\n" + resultLine(SAFE_TESTS[suiteIndex - 1]);
        return screen("Pruebas guiadas — " + progress,
            lastInfo + "\n\n" + last + "\n\nPRÓXIMA: " + progress
                + "\nPulsa Ejecutar únicamente si deseas iniciar esta prueba."
                + " Si se cierra la JVM, el informe de NeuroCenter muestra "
                + "el identificador de la acción que se estaba ejecutando.",
            buttons("suite-next", "Ejecutar: " + LABELS.getOrDefault(active, active),
                    "suite-skip", "Saltar esta prueba", "report", "Ver informe",
                    "home", "Inicio"));
    }

    private String resultsPage() {
        StringBuilder text = new StringBuilder("NeuroCenter Dev Test Kit " + VERSION + "\n"
                + "Informe local (no se envía nada automáticamente).\n\n");
        for (String key : SAFE_TESTS) text.append(resultLine(key)).append("\n\n");
        text.append(resultLine("network")).append("\n\n")
            .append(resultLine("agenda"));
        return screen("Informe de diagnóstico", text.toString(),
            buttons("all", "Repetir pruebas guiadas", "home", "Inicio"));
    }

    private String resultLine(String key) {
        Result result = results.get(key);
        String name = LABELS.getOrDefault(key, key);
        if (result == null) return name + ": SIN PROBAR";
        return name + ": " + result.state() + "\n" + result.message();
    }

    private String shortSummary() {
        int ok = 0, warnings = 0;
        for (Result value : results.values()) {
            if ("OK".equals(value.state())) ok++;
            else warnings++;
        }
        return ok + " OK / " + warnings + " avisos";
    }

    private void runTest(String key) {
        Result result;
        try {
            result = switch (key) {
                case "runtime" -> runtime();
                case "classes" -> classes();
                case "resources" -> resources();
                case "threads" -> threads();
                case "memory" -> memory();
                case "speed" -> speed();
                case "sandbox" -> sandbox();
                case "network" -> network();
                default -> new Result("ERROR", "Prueba desconocida");
            };
        } catch (Throwable problem) {
            result = new Result("ERROR", safeError(problem));
        }
        results.put(key, result);
        lastInfo = resultLine(key);
    }

    private static Result runtime() {
        Runtime runtime = Runtime.getRuntime();
        int feature = Runtime.version().feature();
        String info = "Java: " + System.getProperty("java.version", "?")
                + "\nVM: " + System.getProperty("java.vm.name", "?")
                + "\nVendor: " + System.getProperty("java.vendor", "?")
                + "\nClass version: " + System.getProperty("java.class.version", "?")
                + "\nSO: " + System.getProperty("os.name", "?")
                + " / " + System.getProperty("os.arch", "?")
                + "\nProcesadores visibles: " + runtime.availableProcessors()
                + "\nSecurityManager: " + (System.getSecurityManager() == null
                     ? "no instalado (el aislamiento real depende de Android)" : "instalado");
        return new Result(feature == 21 ? "OK" : "AVISO", info);
    }

    private static Result classes() {
        ProbeRecord probe = new ProbeRecord("OK", 21);
        String loader = String.valueOf(DevTestKitMobile.class.getClassLoader());
        boolean check = "OK".equals(probe.text()) && probe.number() == 21
                && "ok-dependencia".equals(ProbeDependency.value());
        String context = Thread.currentThread().getContextClassLoader() == null
                ? "bootstrap" : Thread.currentThread().getContextClassLoader().getClass().getName();
        return new Result(check ? "OK" : "ERROR",
            "Record Java: " + probe.text() + " / " + probe.number()
                + "\nDependencia de otro .class: " + ProbeDependency.value()
                + "\nClassLoader: " + loader
                + "\nClassLoader del hilo: " + context);
    }

    private Result resources() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/dev-test-kit/probe.txt")) {
            if (in == null) return new Result("ERROR", "No se encuentra el recurso empaquetado.");
            byte[] bytes = in.readNBytes(1024);
            String actual = new String(bytes, StandardCharsets.UTF_8);
            return new Result(SAMPLE.equals(actual) ? "OK" : "ERROR",
                "Recurso interno encontrado: " + bytes.length + " bytes"
                    + "\nContenido esperado: " + SAMPLE.trim());
        }
    }

    private static Result threads() throws Exception {
        AtomicInteger value = new AtomicInteger();
        Thread worker = new Thread(() -> value.set(42), "ncapp-devkit-test");
        worker.setDaemon(true);
        worker.start();
        worker.join(1200);
        boolean passed = !worker.isAlive() && value.get() == 42;
        return new Result(passed ? "OK" : "AVISO",
            "Hilo secundario: " + (passed ? "ejecutado y terminado" : "sin resultado esperado")
                + "\nHilo actual: " + Thread.currentThread().getName());
    }

    private static Result memory() {
        Runtime runtime = Runtime.getRuntime();
        byte[] sample = new byte[256 * 1024];
        Arrays.fill(sample, (byte) 0x5a);
        boolean verified = sample[0] == (byte) 0x5a && sample[sample.length - 1] == (byte) 0x5a;
        long used = runtime.totalMemory() - runtime.freeMemory();
        return new Result(verified ? "OK" : "ERROR",
            "Reserva controlada: 256 KiB; " + (verified ? "correcta" : "incorrecta")
                + "\nHeap usado: " + mib(used) + " MiB"
                + "\nHeap actual: " + mib(runtime.totalMemory()) + " MiB"
                + "\nLímite de heap: " + mib(runtime.maxMemory()) + " MiB");
    }

    private static Result speed() {
        long sum = 0;
        for (int i = 0; i < 10_000; i++) sum += i * 3L;
        long start = System.nanoTime();
        for (int i = 0; i < 150_000; i++) sum += i * 7L + 1L;
        long elapsed = System.nanoTime() - start;
        return new Result(elapsed >= 0 && sum > 0 ? "OK" : "ERROR",
            "Operaciones aritméticas: 150.000\nTiempo: "
                + String.format(java.util.Locale.ROOT, "%.2f ms", elapsed / 1_000_000.0)
                + "\nChecksum: " + sum
                + "\nMedición orientativa, NO un benchmark reproducible.");
    }

    private static Result sandbox() {
        StringBuilder status = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(Path.of("/proc/self/status"))) {
            String line;
            int found = 0;
            while ((line = reader.readLine()) != null && found < 3) {
                if (line.startsWith("Uid:") || line.startsWith("Seccomp:")
                        || line.startsWith("NoNewPrivs:")) {
                    status.append(line).append('\n');
                    found++;
                }
            }
        } catch (Throwable problem) {
            status.append("/proc/self/status: ").append(safeError(problem)).append('\n');
        }
        String path = "/data/user/0/com.neurocenter/files";
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(Path.of(path))) {
            status.append("ALERTA: se pudo abrir el directorio privado del anfitrión.\n");
            return new Result("ALERTA", status.toString());
        } catch (AccessDeniedException | SecurityException denied) {
            status.append("Datos privados del anfitrión: acceso denegado.\n");
            return new Result("OK", status.toString());
        } catch (NoSuchFileException absent) {
            status.append("Ruta privada no visible (no confirma por sí solo el aislamiento).\n");
            return new Result("AVISO", status.toString());
        } catch (Exception problem) {
            status.append("Directorio privado: ").append(safeError(problem))
                    .append(" (no concluyente).\n");
            return new Result("AVISO", status.toString());
        }
    }

    /** Only loopback, no external hosts and no data sent. Never run automatically. */
    private static Result network() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9), 300);
            return new Result("ALERTA", "Se permitió una conexión TCP al loopback. "
                    + "Revisar la política de red del sandbox.");
        } catch (SecurityException denied) {
            return new Result("OK", "Android/Java bloqueó la conexión local: "
                    + safeError(denied));
        } catch (ConnectException refused) {
            return new Result("AVISO", "La conexión se rechazó (no había servicio), "
                    + "pero se llegó a intentar la conexión de loopback.");
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            return new Result(message.contains("permission denied") || message.contains("not permitted")
                    ? "OK" : "AVISO", "Resultado de TCP local: " + safeError(error));
        } catch (LinkageError error) {
            return new Result("AVISO", "Biblioteca de red no disponible: " + safeError(error));
        }
    }

    private static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String safeError(Throwable error) {
        String message = String.valueOf(error.getMessage());
        // Never print exception paths or full stack traces in normal UI.
        if (message.length() > 160) message = message.substring(0, 160) + "…";
        return error.getClass().getSimpleName() + (message.equals("null") ? "" : ": " + message);
    }

    private static String screen(String title, String content, List<String[]> actions) {
        StringBuilder out = new StringBuilder("{\"title\":\"");
        out.append(escape(title)).append("\",\"text\":\"");
        if (content.length() > MAX_REPORT_CHARS) content = content.substring(0, MAX_REPORT_CHARS) + "…";
        out.append(escape(content)).append("\",\"actions\":[");
        boolean first = true;
        for (String[] action : actions) {
            if (!first) out.append(',');
            out.append("{\"id\":\"").append(escape(action[0]))
                .append("\",\"label\":\"").append(escape(action[1])).append("\"}");
            first = false;
        }
        return out.append("]}").toString();
    }

    private static List<String[]> buttons(String... pairs) {
        if (pairs.length % 2 != 0 || pairs.length / 2 > 12) {
            throw new IllegalArgumentException("Invalid UI actions");
        }
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new String[]{pairs[i], pairs[i + 1]});
        return out;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
