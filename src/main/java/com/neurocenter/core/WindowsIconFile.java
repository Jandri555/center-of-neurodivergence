package com.neurocenter.core;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Convierte un PNG en un ICO de 256×256 con transparencia, compatible con Windows moderno. */
final class WindowsIconFile {

    private static final int SIZE = 256;

    private WindowsIconFile() {}

    static Path write(Path png, Path destination) throws IOException {
        BufferedImage source = ImageIO.read(png.toFile());
        if (source == null) throw new IOException("El icono personalizado no es una imagen válida.");

        BufferedImage canvas = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.min(SIZE / (double) source.getWidth(), SIZE / (double) source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            graphics.drawImage(source, (SIZE - width) / 2, (SIZE - height) / 2, width, height, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", pngBytes)) {
            throw new IOException("No se pudo codificar el icono para Windows.");
        }

        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(destination))) {
            writeShort(output, 0); // reserved
            writeShort(output, 1); // icon resource
            writeShort(output, 1); // one image
            output.writeByte(0);   // 256 px is encoded as 0
            output.writeByte(0);
            output.writeByte(0);   // no palette
            output.writeByte(0);
            writeShort(output, 1);  // planes
            writeShort(output, 32); // bits per pixel
            writeInt(output, pngBytes.size());
            writeInt(output, 22);   // ICONDIR + ICONDIRENTRY
            pngBytes.writeTo(output);
        }
        return destination.toAbsolutePath().normalize();
    }

    private static void writeShort(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xFF);
        output.writeByte((value >>> 8) & 0xFF);
    }

    private static void writeInt(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xFF);
        output.writeByte((value >>> 8) & 0xFF);
        output.writeByte((value >>> 16) & 0xFF);
        output.writeByte((value >>> 24) & 0xFF);
    }
}
