package com.neurocenter.core;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Locale;

/**
 * Secure per-user secret storage backed by Windows Credential Manager.
 *
 * <p>Secrets are stored as generic credentials with local-machine persistence.
 * Windows protects the credential data for the current Windows user. The
 * native API is called only from trusted NeuroCenter code, never directly
 * from an externally loaded app.</p>
 */
@SuppressWarnings("removal")
public final class WindowsCredentialVault {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final int MAX_CREDENTIAL_BLOB_SIZE = 5 * 512;

    private WindowsCredentialVault() {}

    public static void save(String appId, String key, char[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("El secreto no puede estar vacío.");
        }
        ensureWindows();
        String target = targetName(appId, key);
        char[] copy = secret.clone();
        try {
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                savePrivileged(target, copy);
                return null;
            });
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /**
     * Returns a new char array, or null when the credential does not exist.
     * The caller is responsible for clearing the returned array when finished.
     */
    public static char[] load(String appId, String key) {
        ensureWindows();
        String target = targetName(appId, key);
        return AccessController.doPrivileged(
                (PrivilegedAction<char[]>) () -> loadPrivileged(target));
    }

    public static void delete(String appId, String key) {
        ensureWindows();
        String target = targetName(appId, key);
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            deletePrivileged(target);
            return null;
        });
    }

    private static void savePrivileged(String target, char[] secret) {
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret));
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("El secreto contiene caracteres no válidos.", error);
        }

        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);

        if (bytes.length == 0 || bytes.length > MAX_CREDENTIAL_BLOB_SIZE) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("El secreto supera el tamaño admitido por Windows Credential Manager.");
        }

        Memory nativeBlob = new Memory(bytes.length);
        try {
            nativeBlob.write(0, bytes, 0, bytes.length);
            Credential credential = new Credential();
            credential.flags = 0;
            credential.type = CRED_TYPE_GENERIC;
            credential.targetName = new WString(target);
            credential.comment = new WString("Center of Neurodivergence application secret");
            credential.lastWritten = new FileTime();
            credential.credentialBlobSize = bytes.length;
            credential.credentialBlob = nativeBlob;
            credential.persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.attributeCount = 0;
            credential.attributes = null;
            credential.targetAlias = null;
            credential.userName = new WString("application-secret");
            credential.write();

            if (Advapi32.INSTANCE.CredWriteW(credential, 0) == 0) {
                throw windowsError("No se pudo guardar la credencial");
            }
        } finally {
            nativeBlob.clear();
            nativeBlob.close();
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static char[] loadPrivileged(String target) {
        PointerByReference result = new PointerByReference();
        if (Advapi32.INSTANCE.CredReadW(new WString(target), CRED_TYPE_GENERIC, 0, result) == 0) {
            int error = Native.getLastError();
            if (error == ERROR_NOT_FOUND) return null;
            throw windowsError("No se pudo leer la credencial", error);
        }

        Pointer credentialPointer = result.getValue();
        try {
            Credential credential = new Credential(credentialPointer);
            credential.read();
            if (credential.credentialBlobSize <= 0 || credential.credentialBlob == null) {
                return new char[0];
            }
            if (credential.credentialBlobSize > MAX_CREDENTIAL_BLOB_SIZE) {
                throw new IllegalStateException("Windows devolvió una credencial de tamaño no válido.");
            }

            byte[] bytes = credential.credentialBlob.getByteArray(0, credential.credentialBlobSize);
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
                char[] value = new char[decoded.remaining()];
                decoded.get(value);
                if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
                return value;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            Advapi32.INSTANCE.CredFree(credentialPointer);
        }
    }

    private static void deletePrivileged(String target) {
        if (Advapi32.INSTANCE.CredDeleteW(new WString(target), CRED_TYPE_GENERIC, 0) == 0) {
            int error = Native.getLastError();
            if (error != ERROR_NOT_FOUND) {
                throw windowsError("No se pudo eliminar la credencial", error);
            }
        }
    }

    private static String targetName(String appId, String key) {
        if (appId == null || !appId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Identificador de aplicación no válido.");
        }
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Clave de credencial no válida.");
        }
        return "CenterOfNeurodivergence:" + appId + ":" + key;
    }

    private static void ensureWindows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new UnsupportedOperationException(
                    "El almacén de credenciales de Windows solo está disponible en Windows.");
        }
    }

    private static IllegalStateException windowsError(String message) {
        return windowsError(message, Native.getLastError());
    }

    private static IllegalStateException windowsError(String message, int code) {
        return new IllegalStateException(message + " (error de Windows " + code + ").");
    }

    private interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class);

        int CredWriteW(Credential credential, int flags);
        int CredReadW(WString targetName, int type, int flags, PointerByReference credential);
        int CredDeleteW(WString targetName, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({
            "flags", "type", "targetName", "comment", "lastWritten",
            "credentialBlobSize", "credentialBlob", "persist", "attributeCount",
            "attributes", "targetAlias", "userName"
    })
    public static class Credential extends Structure {
        public int flags;
        public int type;
        public WString targetName;
        public WString comment;
        public FileTime lastWritten = new FileTime();
        public int credentialBlobSize;
        public Pointer credentialBlob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public WString targetAlias;
        public WString userName;

        public Credential() {
            super();
        }

        public Credential(Pointer pointer) {
            super(pointer);
        }
    }

    @Structure.FieldOrder({"lowDateTime", "highDateTime"})
    public static class FileTime extends Structure {
        public int lowDateTime;
        public int highDateTime;
    }
}