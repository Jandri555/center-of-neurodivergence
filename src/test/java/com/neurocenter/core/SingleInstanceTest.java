package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceTest {

    @TempDir
    Path tempDir;

    @Test
    void secondLaunchActivatesExistingInstanceAndDoesNotAcquireAnotherLock() throws Exception {
        CountDownLatch activated = new CountDownLatch(1);
        AtomicInteger activations = new AtomicInteger();

        SingleInstance first = SingleInstance.acquireOrActivate(tempDir);
        assertNotNull(first, "La primera ejecución debe adquirir el bloqueo.");

        try {
            first.setActivationHandler(() -> {
                activations.incrementAndGet();
                activated.countDown();
            });

            SingleInstance second = SingleInstance.acquireOrActivate(tempDir);

            assertNull(second, "La segunda ejecución debe activar la primera y salir.");
            assertTrue(activated.await(2, TimeUnit.SECONDS),
                    "La instancia principal debe recibir la orden de activación.");
            assertEquals(1, activations.get());
        } finally {
            first.close();
        }

        try (SingleInstance replacement = SingleInstance.acquireOrActivate(tempDir)) {
            assertNotNull(replacement,
                    "Tras cerrar la instancia principal, una nueva ejecución debe poder adquirir el bloqueo.");
        }
    }
}
