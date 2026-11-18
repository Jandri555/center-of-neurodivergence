package com.neurocenter.securitypoc;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executable proof of concept for Java 21's legacy Security Manager.
 *
 * Run this class from its own JAR with a restrictive policy file. It checks
 * that ordinary Java file, socket, native-library and Security Manager
 * operations are denied unless explicitly granted.
 *
 * This is an experiment, not a security boundary for hostile code.
 */
public final class SecurityManagerPoc {

    private static int passed;
    private static int failed;

    private SecurityManagerPoc() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java ... -jar security-manager-poc.jar <allowed-directory> <forbidden-file>");
            System.exit(2);
        }

        Path allowedDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path forbiddenFile = Path.of(args[1]).toAbsolutePath().normalize();

        System.out.println("Security Manager active: " + (System.getSecurityManager() != null));
        System.out.println("Allowed directory: " + allowedDirectory);
        System.out.println("Forbidden file: " + forbiddenFile);
        System.out.println();

        expectAllowed("Read a file inside the granted directory", () -> {
            String contents = Files.readString(allowedDirectory.resolve("fixture.txt"));
            if (!"allowed-fixture".equals(contents)) {
                throw new IllegalStateException("Unexpected fixture contents: " + contents);
            }
        });

        expectAllowed("Write a file inside the granted directory", () ->
                Files.writeString(allowedDirectory.resolve("created-by-app.txt"), "written"));

        expectDenied("Read a file outside the granted directory (java.io)", () -> {
            try (FileInputStream input = new FileInputStream(forbiddenFile.toFile())) {
                input.readAllBytes();
            }
        });

        expectDenied("Read a file outside the granted directory (NIO)", () ->
                Files.readString(forbiddenFile));

        expectDenied("Write a file outside the granted directory", () ->
                Files.writeString(forbiddenFile.resolveSibling("blocked-write.txt"), "must-not-exist"));

        expectDenied("Escape the allowed directory with '..'", () ->
                Files.readString(allowedDirectory.resolve("../forbidden.txt")));

        expectDenied("Delete a file outside the granted directory", () ->
                Files.delete(forbiddenFile));

        expectDenied("Open a listening network socket", () -> {
            try (ServerSocket socket = new ServerSocket(0)) {
                // Opening the socket itself is the operation under test.
            }
        });

        expectDenied("Load a native library", () ->
                System.loadLibrary("neurocenter_poc_library_that_does_not_exist"));

        expectDenied("Disable the Security Manager", () ->
                System.setSecurityManager(null));

        System.out.println();
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void expectAllowed(String label, CheckedOperation operation) throws Exception {
        try {
            operation.run();
            passed++;
            System.out.println("[ALLOWED as expected] " + label);
        } catch (Exception | LinkageError error) {
            failed++;
            System.out.println("[UNEXPECTED FAILURE] " + label + " -> " + error);
        }
    }

    private static void expectDenied(String label, CheckedOperation operation) throws Exception {
        try {
            operation.run();
            failed++;
            System.out.println("[NOT BLOCKED] " + label);
        } catch (SecurityException expected) {
            passed++;
            System.out.println("[BLOCKED as expected] " + label + " -> " + expected.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
