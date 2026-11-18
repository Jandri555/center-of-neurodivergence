package com.neurocenter.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Actualiza los accesos directos de Windows que apuntan al ejecutable de NeuroCenter.
 * No modifica accesos de terceros y restaura el icono del EXE cuando no hay icono personalizado.
 */
public final class WindowsShortcutManager {

    private static final String POWERSHELL_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $target = [IO.Path]::GetFullPath($env:NC_SHORTCUT_TARGET)
            $icon = $env:NC_SHORTCUT_ICON
            $roots = $env:NC_SHORTCUT_ROOTS -split ';'
            $shell = New-Object -ComObject WScript.Shell
            $updated = 0

            foreach ($root in $roots) {
                if ([string]::IsNullOrWhiteSpace($root) -or -not (Test-Path -LiteralPath $root)) {
                    continue
                }
                Get-ChildItem -LiteralPath $root -Filter '*.lnk' -File -Recurse -ErrorAction SilentlyContinue |
                    ForEach-Object {
                        try {
                            $shortcut = $shell.CreateShortcut($_.FullName)
                            if ([string]::IsNullOrWhiteSpace($shortcut.TargetPath)) { return }
                            $shortcutTarget = [IO.Path]::GetFullPath($shortcut.TargetPath)
                            if ([string]::Equals($shortcutTarget, $target,
                                    [StringComparison]::OrdinalIgnoreCase)) {
                                if ([string]::IsNullOrWhiteSpace($icon)) {
                                    $shortcut.IconLocation = $target + ',0'
                                } else {
                                    $shortcut.IconLocation = $icon + ',0'
                                }
                                $shortcut.Save()
                                $updated++
                            }
                        } catch { }
                    }
            }
            Write-Output $updated
            """;

    private WindowsShortcutManager() {}

    /**
     * @param customIcon ruta del ICO personalizado; null restaura el icono integrado en el EXE.
     * @return número de accesos directos actualizados.
     */
    public static int refresh(Path customIcon) throws IOException, InterruptedException {
        if (!isWindows()) return 0;

        Path executable = applicationExecutable();
        if (executable == null) {
            throw new IOException("No se pudo localizar el ejecutable instalado de NeuroCenter.");
        }
        if (customIcon != null && !Files.isRegularFile(customIcon)) {
            throw new IOException("No se encuentra el icono personalizado: " + customIcon);
        }

        List<String> roots = shortcutRoots();
        if (roots.isEmpty()) return 0;

        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", POWERSHELL_SCRIPT);
        Map<String, String> environment = builder.environment();
        environment.put("NC_SHORTCUT_TARGET", executable.toString());
        environment.put("NC_SHORTCUT_ICON", customIcon == null ? "" : customIcon.toAbsolutePath().toString());
        environment.put("NC_SHORTCUT_ROOTS", String.join(";", roots));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("La actualización de accesos directos de Windows ha superado el tiempo límite.");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim();
        if (process.exitValue() != 0) {
            throw new IOException("PowerShell no pudo actualizar los accesos directos. " + output);
        }
        try {
            return Integer.parseInt(output.lines().reduce((first, second) -> second).orElse("0").trim());
        } catch (NumberFormatException ex) {
            throw new IOException("Windows no devolvió el número de accesos directos actualizados.", ex);
        }
    }

    private static Path applicationExecutable() {
        String launcherPath = System.getProperty("jpackage.app-path");
        if (launcherPath != null && !launcherPath.isBlank()) {
            Path candidate = Path.of(launcherPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && isApplicationExecutable(candidate)) return candidate;
        }

        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            Path candidate = Path.of(command).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && isApplicationExecutable(candidate)) return candidate;
        }

        try {
            Path location = Path.of(WindowsShortcutManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path appDirectory = Files.isDirectory(location) ? location : location.getParent();
            if (appDirectory != null && appDirectory.getFileName() != null
                    && "app".equalsIgnoreCase(appDirectory.getFileName().toString())) {
                Path candidate = appDirectory.getParent().resolve(AppInfo.NAME + ".exe");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // En ejecución desde el IDE no hay un lanzador jpackage que modificar.
        }
        return null;
    }

    private static boolean isApplicationExecutable(Path candidate) {
        return candidate.getFileName() != null
                && (AppInfo.NAME + ".exe").equalsIgnoreCase(candidate.getFileName().toString());
    }

    private static List<String> shortcutRoots() {
        List<String> roots = new ArrayList<>();
        addEnvironmentPath(roots, "APPDATA", "Microsoft", "Windows", "Start Menu", "Programs");
        addEnvironmentPath(roots, "PROGRAMDATA", "Microsoft", "Windows", "Start Menu", "Programs");
        addEnvironmentPath(roots, "USERPROFILE", "Desktop");
        addEnvironmentPath(roots, "PUBLIC", "Desktop");
        addEnvironmentPath(roots, "APPDATA", "Microsoft", "Internet Explorer",
                "Quick Launch", "User Pinned", "TaskBar");
        return roots;
    }

    private static void addEnvironmentPath(List<String> paths, String variable, String... parts) {
        String base = System.getenv(variable);
        if (base == null || base.isBlank()) return;
        Path path = Path.of(base, parts);
        String value = path.toAbsolutePath().normalize().toString();
        if (!paths.contains(value)) paths.add(value);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
