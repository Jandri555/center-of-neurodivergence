package com.neurocenter.core;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Almacén de secretos de Android. Cifra cada valor con una clave AES-GCM
 * independiente guardada en Android Keystore y limita los archivos a la carpeta
 * privada de datos de la app propietaria.
 *
 * El llamante debe comprobar credentials.access antes de invocar esta clase.
 */
public final class AndroidCredentialVault {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PREFIX = "neurocenter.app.";

    private AndroidCredentialVault() {}

    public static synchronized void save(Context context, String appId, String name, char[] secret)
            throws IOException {
        validate(appId, name);
        if (secret == null) throw new IllegalArgumentException("El secreto no puede ser null.");
        String alias = alias(appId, name);
        byte[] plain = encode(secret);
        byte[] encrypted = null;
        byte[] iv = null;
        try {
            SecretKey key = getOrCreateKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            iv = cipher.getIV();
            encrypted = cipher.doFinal(plain);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(iv.length);
                out.write(iv);
                out.writeInt(encrypted.length);
                out.write(encrypted);
            }

            Path directory = AppPaths.appDataDir(context, appId).resolve("credentials");
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName(name));
            Path temporary = directory.resolve(fileName(name) + ".tmp");
            try {
                Files.write(temporary, bytes.toByteArray());
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
                byte[] serialized = bytes.toByteArray();
                Arrays.fill(serialized, (byte) 0);
            }
        } catch (Exception ex) {
            if (ex instanceof IOException io) throw io;
            throw new IOException("No se pudo guardar la credencial en Android Keystore.", ex);
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    public static synchronized char[] load(Context context, String appId, String name)
            throws IOException {
        validate(appId, name);
        Path file = AppPaths.appDataDir(context, appId).resolve("credentials").resolve(fileName(name));
        if (!Files.isRegularFile(file)) return null;
        byte[] iv = null;
        byte[] encrypted = null;
        byte[] plain = null;
        try {
            long size = Files.size(file);
            if (size < 32 || size > 1024 * 1024) throw new IOException("El archivo de credenciales no es válido.");
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                int ivLength = in.readInt();
                if (ivLength < 12 || ivLength > 16) throw new IOException("Vector de inicialización no válido.");
                iv = new byte[ivLength];
                in.readFully(iv);
                int encryptedLength = in.readInt();
                if (encryptedLength < 16 || encryptedLength > 1024 * 1024
                        || encryptedLength != in.available()) {
                    throw new IOException("Contenido cifrado no válido.");
                }
                encrypted = new byte[encryptedLength];
                in.readFully(encrypted);
            }
            SecretKey key = getExistingKey(alias(appId, name));
            if (key == null) throw new IOException("No existe la clave de Android Keystore.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            plain = cipher.doFinal(encrypted);
            return decode(plain);
        } catch (Exception ex) {
            if (ex instanceof IOException io) throw io;
            throw new IOException("No se pudo descifrar la credencial.", ex);
        } finally {
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public static synchronized void delete(Context context, String appId, String name)
            throws IOException {
        validate(appId, name);
        Path file = AppPaths.appDataDir(context, appId).resolve("credentials").resolve(fileName(name));
        Files.deleteIfExists(file);
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            store.deleteEntry(alias(appId, name));
        } catch (Exception ex) {
            throw new IOException("No se pudo eliminar la clave de Android Keystore.", ex);
        }
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        SecretKey existing = getExistingKey(alias);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SecretKey getExistingKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key key = store.getKey(alias, null);
        return key instanceof SecretKey secret ? secret : null;
    }

    private static byte[] encode(char[] secret) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(secret));
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        if (buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
        return result;
    }

    private static char[] decode(byte[] plain) throws CharacterCodingException {
        CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain));
        char[] result = new char[buffer.remaining()];
        buffer.get(result);
        if (buffer.hasArray()) Arrays.fill(buffer.array(), '\u0000');
        return result;
    }

    private static String alias(String appId, String name) throws Exception {
        return PREFIX + digest(appId + ":" + name);
    }

    private static String fileName(String name) throws IOException {
        try {
            return digest(name) + ".bin";
        } catch (Exception ex) {
            throw new IOException("No se pudo calcular el identificador de credencial.", ex);
        }
    }

    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    private static void validate(String appId, String name) {
        if (appId == null || !appId.matches("[A-Za-z0-9._-]{1,128}"))
            throw new IllegalArgumentException("Identificador de aplicación no válido.");
        if (name == null || name.trim().isEmpty() || name.length() > 128)
            throw new IllegalArgumentException("Nombre de credencial no válido.");
    }
}
