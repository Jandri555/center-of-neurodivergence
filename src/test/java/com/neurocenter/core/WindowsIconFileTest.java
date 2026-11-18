package com.neurocenter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsIconFileTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAValidPngBackedWindowsIconAndPreservesAspectRatio() throws Exception {
        Path source = tempDir.resolve("source.png");
        Path destination = tempDir.resolve("center-icon.ico");
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "png", source.toFile());

        WindowsIconFile.write(source, destination);

        byte[] ico = Files.readAllBytes(destination);
        assertTrue(ico.length > 22);
        ByteBuffer header = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, Short.toUnsignedInt(header.getShort()));
        assertEquals(1, Short.toUnsignedInt(header.getShort()));
        assertEquals(1, Short.toUnsignedInt(header.getShort()));
        assertEquals(0, Byte.toUnsignedInt(header.get())); // 256 px
        assertEquals(0, Byte.toUnsignedInt(header.get()));
        assertEquals(22, header.getInt(18));

        BufferedImage embedded = ImageIO.read(new ByteArrayInputStream(ico, 22, ico.length - 22));
        assertEquals(256, embedded.getWidth());
        assertEquals(256, embedded.getHeight());
        assertTrue((embedded.getRGB(0, 0) >>> 24) == 0, "El margen debe conservar la transparencia.");
        assertTrue((embedded.getRGB(128, 128) >>> 24) > 0, "La imagen debe quedar centrada.");
    }
}
