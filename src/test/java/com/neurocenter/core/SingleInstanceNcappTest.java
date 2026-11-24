package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceNcappTest {
    @TempDir Path directory;

    @Test
    void forwardsNcappFileToExistingInstance() throws Exception {
        Path archive = directory.resolve("test.ncapp");
        Files.writeString(archive, "test");
        try (SingleInstance first = SingleInstance.acquireOrActivate(directory)) {
            assertNotNull(first);
            assertNull(SingleInstance.acquireOrActivate(directory, archive),
                    "El doble clic no debe abrir un segundo NeuroCenter");
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<Path> received = new AtomicReference<>();
            first.setNcappOpenHandler(path -> {
                received.set(path);
                opened.countDown();
            });
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            assertEquals(archive.toAbsolutePath().normalize(), received.get());
        }
    }

    @Test
    void activatingExistingInstanceKeepsSingleProcess() throws Exception {
        try (SingleInstance first = SingleInstance.acquireOrActivate(directory)) {
            CountDownLatch called = new CountDownLatch(1);
            first.setActivationHandler(called::countDown);
            assertNull(SingleInstance.acquireOrActivate(directory));
            assertTrue(called.await(3, TimeUnit.SECONDS));
        }
    }
}
