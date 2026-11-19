#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>

/* Older Android NDK sysroot headers may omit Linux memfd seal constants. */
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#endif
#ifndef F_SEAL_SEAL
#define F_SEAL_SEAL 0x0001
#endif
#ifndef F_SEAL_SHRINK
#define F_SEAL_SHRINK 0x0002
#endif
#ifndef F_SEAL_GROW
#define F_SEAL_GROW 0x0004
#endif
#ifndef F_SEAL_WRITE
#define F_SEAL_WRITE 0x0008
#endif

/*
 * OpenJDK 21 launcher inside a real isolatedProcess. The Android host only
 * provides read-only descriptors to the JRE image and the selected .ncapp.
 * Java code returns JSON; it never receives references to Android host APIs.
 *
 * This path still needs validation on hardware (SELinux and HotSpot/ART).
 */
#define TAG "NCAPP-JAVA21"
#define MAX_JSON_UTF16 16000
typedef jint(JNICALL *create_java_vm_t)(JavaVM **, void **, void *);
static JavaVM *g_vm = NULL;
static jobject g_app = NULL;
static jmethodID g_action = NULL;

/*
 * DEBUG ONLY: process stdout/stderr and HotSpot's vfprintf hook are piped
 * to the host over a one-way Binder-delivered FD. A native exit() cannot
 * erase bytes already written into the kernel pipe.
 * No sandbox privilege changes, logcat permission or exit interception.
 */
#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
static int g_debug_pipe = -1;
static void pipe_message(const char *line) {
    if (g_debug_pipe < 0 || line == NULL) return;
    size_t len = strnlen(line, 2048);
    if (len) (void)write(g_debug_pipe, line, len);
    (void)write(g_debug_pipe, "\n", 1);
}
#endif

static void ncapp_note(int priority, const char *format, ...) {
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
    __android_log_write(priority, TAG, message);
    pipe_message(message);
#else
    // Release logs only errors, without native diagnostic infrastructure.
    if (priority >= ANDROID_LOG_ERROR) __android_log_write(priority, TAG, message);
#endif
}

#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
static int hotspot_vfprintf(FILE *stream, const char *format, va_list args) {
    char message[2048];
    va_list copy;
    va_copy(copy, args);
    int count = vsnprintf(message, sizeof(message), format, copy);
    va_end(copy);
    if (count >= 0 && message[0] != '\0') {
        ncapp_note(stream == stderr ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                   "HotSpot output: %s", message);
    }
    return count;
}

static void enable_debug_pipe(int received_fd) {
    if (received_fd < 0) return;
    g_debug_pipe = dup(received_fd);
    if (g_debug_pipe < 0) return;
    // Capture output not handled by the JVM's JNI vfprintf hook as well.
    // This affects only this separate Android isolated process.
    if (dup2(received_fd, STDERR_FILENO) < 0
            || dup2(received_fd, STDOUT_FILENO) < 0) {
        ncapp_note(ANDROID_LOG_ERROR, "No se pudo redirigir stdout/stderr: %d", errno);
    }
    ncapp_note(ANDROID_LOG_INFO, "DIAGNÓSTICO: tubería nativa conectada");
}
#endif

#define MAX_NCAPP_BYTES (128L * 1024L * 1024L)

/*
 * A Binder-received, read-only descriptor is accessible for read()/pread()
 * but OpenJDK's classpath loader needs to open the JAR again by *path*.
 * /proc/self/fd/<binder-fd> can be blocked by Android SELinux because it
 * points to a host-owned app_data_file even when the original FD is readable.
 *
 * Instead, make a *sealed, non-executable in-memory copy* owned by the isolated
 * UID. The host never supplies a directory, path permission or executable.
 * Keep the memfd open until process death because ZipFile may reopen it later.
 */
static int g_classpath_fd = -1;
static int make_isolated_classpath(int source_fd, char *path, size_t path_size) {
    if (g_classpath_fd >= 0) {
        errno = EALREADY;
        return -1;
    }
    struct stat sb;
    if (fstat(source_fd, &sb) != 0 || !S_ISREG(sb.st_mode)
            || sb.st_size <= 0 || sb.st_size > MAX_NCAPP_BYTES) {
        errno = EINVAL;
        return -1;
    }

#if defined(SYS_memfd_create)
    /* MFD_NOEXEC_SEAL is Linux 6.3+, so fall back for older Android kernels. */
    const unsigned int noexec_seal = 0x0008U;
    int fd = (int)syscall(SYS_memfd_create, "neurocenter-ncapp",
                          MFD_CLOEXEC | MFD_ALLOW_SEALING | noexec_seal);
    if (fd < 0 && errno == EINVAL) {
        fd = (int)syscall(SYS_memfd_create, "neurocenter-ncapp",
                          MFD_CLOEXEC | MFD_ALLOW_SEALING);
    }
#else
    errno = ENOSYS;
    int fd = -1;
#endif
    if (fd < 0) return -1;

    char buf[32768];
    off_t at = 0;
    while (at < sb.st_size) {
        size_t request = (size_t)(sb.st_size - at);
        if (request > sizeof(buf)) request = sizeof(buf);
        ssize_t n = pread(source_fd, buf, request, at);
        if (n <= 0) {
            if (n == 0) errno = EIO;
            close(fd);
            return -1;
        }
        ssize_t offset = 0;
        while (offset < n) {
            ssize_t written = write(fd, buf + offset, (size_t)(n - offset));
            if (written <= 0) {
                if (written == 0) errno = EIO;
                close(fd);
                return -1;
            }
            offset += written;
        }
        at += n;
    }
    /* Always seal writes. If unavailable, DO NOT use an unsealed archive. */
    if (fcntl(fd, F_ADD_SEALS,
              F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) != 0) {
        close(fd);
        return -1;
    }
    if (lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }

    int count = snprintf(path, path_size, "/proc/self/fd/%d", fd);
    if (count < 0 || (size_t)count >= path_size) {
        errno = ENAMETOOLONG;
        close(fd);
        return -1;
    }
    /* Verifies reopening the *isolated* memfd, not the original host file. */
    int probe = open(path, O_RDONLY | O_CLOEXEC);
    if (probe < 0) {
        close(fd);
        return -1;
    }
    unsigned char magic[4];
    ssize_t read_bytes = pread(probe, magic, sizeof magic, 0);
    close(probe);
    if (read_bytes != 4 || magic[0] != 0x50 || magic[1] != 0x4b) {
        errno = EINVAL;
        close(fd);
        return -1;
    }
    g_classpath_fd = fd;
    ncapp_note(ANDROID_LOG_INFO,
               "JVM classpath: sealed isolated memfd (%lld bytes)",
               (long long)sb.st_size);
    return 0;
}

static jstring err(JNIEnv *android_env, const char *name, const char *details) {
    char buffer[1024];
    snprintf(buffer, sizeof buffer, "NCAPP_ERROR:%s: %s", name,
             details == NULL ? "desconocido" : details);
    ncapp_note(ANDROID_LOG_ERROR, "%s", buffer);
    return (*android_env)->NewStringUTF(android_env, buffer);
}

static jstring java_error(JNIEnv *android_env, JNIEnv *jre, const char *name) {
    if ((*jre)->ExceptionCheck(jre)) {
        (*jre)->ExceptionDescribe(jre);
        (*jre)->ExceptionClear(jre);
    }
    return err(android_env, name, "Excepción de Java 21 (consulta logcat)");
}

static jstring json_result(JNIEnv *android_env, JNIEnv *jre, jobject value) {
    if ((*jre)->ExceptionCheck(jre)) return java_error(android_env, jre, "aplicación");
    if (value == NULL) return err(android_env, "interfaz", "La app devolvió null");
    if ((*jre)->GetStringLength(jre, (jstring)value) > MAX_JSON_UTF16) {
        return err(android_env, "interfaz", "JSON demasiado grande");
    }
    const char *data = (*jre)->GetStringUTFChars(jre, (jstring)value, NULL);
    if (data == NULL) return err(android_env, "interfaz", "UTF-8 no disponible");
    jstring result = (*android_env)->NewStringUTF(android_env, data);
    (*jre)->ReleaseStringUTFChars(jre, (jstring)value, data);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_neurocenter_core_NcappWorkerService_launchJava21(
    JNIEnv *android_env, jclass ignored, jstring jhome,
    jstring jarchive, jstring jentry, jstring jlibs, jint debug_fd) {
    (void)ignored;
#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
    enable_debug_pipe(debug_fd);
#else
    (void)debug_fd;
#endif
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 1: entró al lanzador JNI");
    if (g_vm != NULL) return err(android_env, "estado", "La JVM ya está iniciada");
    if (!jhome || !jarchive || !jentry || !jlibs) {
        return err(android_env, "argumentos", "Faltan parámetros");
    }
    const char *home = (*android_env)->GetStringUTFChars(android_env, jhome, NULL);
    if (!home) return NULL;
    const char *archive = (*android_env)->GetStringUTFChars(android_env, jarchive, NULL);
    const char *entry = (*android_env)->GetStringUTFChars(android_env, jentry, NULL);
    const char *libs = (*android_env)->GetStringUTFChars(android_env, jlibs, NULL);
    if (!archive || !entry || !libs) {
        if (archive) (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        if (entry) (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        if (libs) (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        return NULL;
    }
    char libjvm[PATH_MAX], homeOption[PATH_MAX + 50];
    char libraryOption[PATH_MAX + 50], appOption[PATH_MAX + 50];
    char className[512];
    if (strlen(entry) >= sizeof className) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "clase", "Nombre de clase demasiado largo");
    }
    strcpy(className, entry);
    for (char *p = className; *p; p++) if (*p == '.') *p = '/';
    snprintf(libjvm, sizeof libjvm, "%s/libjvm.so", libs);
    snprintf(homeOption, sizeof homeOption, "-Djava.home=%s", home);
    snprintf(libraryOption, sizeof libraryOption, "-Djava.library.path=%s", libs);
    /* A numeric fd is passed by Binder, never a host path with permissions. */
    const char *prefix = "/proc/self/fd/";
    if (strncmp(archive, prefix, strlen(prefix)) != 0) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "Descriptor inválido");
    }
    char *end = NULL;
    errno = 0;
    long source_fd = strtol(archive + strlen(prefix), &end, 10);
    if (errno != 0 || end == archive + strlen(prefix) || *end != '\0'
            || source_fd < 0 || source_fd > INT_MAX) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "FD no válido");
    }
    char isolated_path[64];
    if (make_isolated_classpath((int)source_fd, isolated_path, sizeof isolated_path) != 0) {
        char details[128];
        snprintf(details, sizeof details, "No se pudo crear JAR aislado en memoria: errno=%d", errno);
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", details);
    }
    snprintf(appOption, sizeof appOption, "-Djava.class.path=%s", isolated_path);
    setenv("JAVA_HOME", home, 1);
    // Read only by a NeuroCenter-patched HotSpot build; it may not be used
    // to grant access to any host-private files from an isolated UID.
    setenv("NEUROCENTER_JRE_HOME", home, 1);
    setenv("LD_LIBRARY_PATH", libs, 1);
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 2: cargando libjvm.so");
    void *handle = dlopen(libjvm, RTLD_NOW | RTLD_GLOBAL);
    (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
    (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
    (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
    (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
    if (!handle) return err(android_env, "libjvm", dlerror());
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 3: dlopen completado");
    create_java_vm_t create = (create_java_vm_t)dlsym(handle, "JNI_CreateJavaVM");
    if (!create) return err(android_env, "símbolo", "JNI_CreateJavaVM no disponible");

    JavaVMOption options[] = {
        {.optionString = homeOption},
        {.optionString = libraryOption},
        {.optionString = appOption},
        {.optionString = "-Djava.awt.headless=true"},
        {.optionString = "-Djava.security.manager=allow"},
        {.optionString = "-Djdk.lang.Process.launchMechanism=FORK"},
        {.optionString = "-Xmx128m"}
#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
        ,{.optionString = "vfprintf", .extraInfo = (void *)hotspot_vfprintf}
#endif
    };
    JavaVMInitArgs args = {
        .version = JNI_VERSION_1_6,
        .nOptions = sizeof options / sizeof options[0],
        .options = options,
        .ignoreUnrecognized = JNI_FALSE
    };
    JNIEnv *jre = NULL;
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 4: entrando en JNI_CreateJavaVM");
    jint status = create(&g_vm, (void**)&jre, &args);
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 5: JNI_CreateJavaVM retornó %d", status);
    if (status != JNI_OK || !g_vm || !jre) {
        char code[64];
        g_vm = NULL;
        snprintf(code, sizeof code, "JNI_CreateJavaVM devolvió %d", status);
        return err(android_env, "arranque", code);
    }

    ncapp_note(ANDROID_LOG_INFO, "JVM stage 6: cargando clase móvil");
    jclass clazz = (*jre)->FindClass(jre, className);
    if (!clazz) return java_error(android_env, jre, "clase móvil");
    jmethodID ctor = (*jre)->GetMethodID(jre, clazz, "<init>", "()V");
    if (!ctor) return java_error(android_env, jre, "constructor");
    jobject instance = (*jre)->NewObject(jre, clazz, ctor);
    if (!instance || (*jre)->ExceptionCheck(jre)) return java_error(android_env, jre, "constructor");
    jmethodID render = (*jre)->GetMethodID(jre, clazz, "renderJson", "()Ljava/lang/String;");
    if (!render) return java_error(android_env, jre, "renderJson");
    g_action = (*jre)->GetMethodID(jre, clazz, "onAction",
                                  "(Ljava/lang/String;)Ljava/lang/String;");
    if (!g_action) return java_error(android_env, jre, "onAction");
    g_app = (*jre)->NewGlobalRef(jre, instance);
    if (!g_app) return err(android_env, "memoria", "No se pudo retener la aplicación");
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 7: invocando renderJson");
    jobject value = (*jre)->CallObjectMethod(jre, g_app, render);
    return json_result(android_env, jre, value);
}

JNIEXPORT jstring JNICALL Java_com_neurocenter_core_NcappWorkerService_callJava21Action(
    JNIEnv *android_env, jclass ignored, jstring actionId) {
    (void)ignored;
    if (!g_vm || !g_app || !g_action || !actionId) {
        return err(android_env, "estado", "La aplicación no está preparada");
    }
    JNIEnv *jre = NULL;
    int attached = 0;
    jint status = (*g_vm)->GetEnv(g_vm, (void**)&jre, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void**)&jre, NULL) != JNI_OK) {
            return err(android_env, "hilo", "HotSpot no pudo adjuntar el hilo");
        }
        attached = 1;
    } else if (status != JNI_OK || !jre) {
        return err(android_env, "hilo", "HotSpot no pudo obtener el entorno");
    }
    const char *action = (*android_env)->GetStringUTFChars(android_env, actionId, NULL);
    if (!action) {
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return NULL;
    }
    jstring javaAction = (*jre)->NewStringUTF(jre, action);
    (*android_env)->ReleaseStringUTFChars(android_env, actionId, action);
    jobject value = (*jre)->CallObjectMethod(jre, g_app, g_action, javaAction);
    jstring result = json_result(android_env, jre, value);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return result;
}


/* La app recibe el resultado de una solicitud al host exclusivamente como JSON. */
JNIEXPORT jstring JNICALL Java_com_neurocenter_core_NcappWorkerService_callJava21HostResponse(
    JNIEnv *android_env, jclass ignored, jstring capability, jstring responseJson) {
    (void)ignored;
    if (!g_vm || !g_app || !capability || !responseJson) {
        return err(android_env, "host", "No hay una aplicación Java preparada");
    }
    JNIEnv *jre = NULL;
    int attached = 0;
    jint status = (*g_vm)->GetEnv(g_vm, (void**)&jre, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void**)&jre, NULL) != JNI_OK) {
            return err(android_env, "host", "No se pudo adjuntar el hilo");
        }
        attached = 1;
    } else if (status != JNI_OK || !jre) {
        return err(android_env, "host", "Entorno Java 21 no disponible");
    }

    jclass clazz = (*jre)->GetObjectClass(jre, g_app);
    jmethodID method = (*jre)->GetMethodID(jre, clazz, "onHostResponse",
                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!method) {
        jstring answer = java_error(android_env, jre, "onHostResponse");
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return answer;
    }
    const char *cap = (*android_env)->GetStringUTFChars(android_env, capability, NULL);
    if (!cap) {
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return NULL;
    }
    const char *json = (*android_env)->GetStringUTFChars(android_env, responseJson, NULL);
    if (!json) {
        (*android_env)->ReleaseStringUTFChars(android_env, capability, cap);
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return NULL;
    }
    jstring jcap = (*jre)->NewStringUTF(jre, cap);
    jstring jresponse = (*jre)->NewStringUTF(jre, json);
    (*android_env)->ReleaseStringUTFChars(android_env, capability, cap);
    (*android_env)->ReleaseStringUTFChars(android_env, responseJson, json);
    jobject screen = (*jre)->CallObjectMethod(jre, g_app, method, jcap, jresponse);
    jstring result = json_result(android_env, jre, screen);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return result;
}
