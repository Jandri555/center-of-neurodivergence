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
#include <sys/stat.h>

#include "streaming_jar_loader_class.h"

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
 * Java 21's filesystem classpath loader cannot safely reopen a host-owned
 * .ncapp path from the Android isolated UID. Instead, a tiny signed Java
 * classloader streams ZIP entries through pread() on the read-only Binder FD.
 * No private directory, /proc/self/fd reopen, memfd, or code extraction.
 */
#define MAX_NCAPP_BYTES (128L * 1024L * 1024L)
static int g_verified_jar_fd = -1;
static jlong g_verified_jar_size = 0;
static jobject g_jar_loader = NULL;

static jint JNICALL read_archive_for_hotspot(JNIEnv *jre, jclass ignored,
                                               jbyteArray target, jint offset,
                                               jint count, jlong position) {
    (void)ignored;
    if (g_verified_jar_fd < 0 || target == NULL || offset < 0 || count < 0
            || position < 0 || position >= g_verified_jar_size
            || count > (*jre)->GetArrayLength(jre, target) - offset) {
        jclass ex = (*jre)->FindClass(jre, "java/io/IOException");
        if (ex) (*jre)->ThrowNew(jre, ex, "JAR descriptor or range is invalid");
        return -1;
    }
    if (count == 0) return 0;
    unsigned char buffer[16384];
    if (count > (jint)sizeof buffer) count = (jint)sizeof buffer;
    if ((jlong)count > g_verified_jar_size - position) {
        count = (jint)(g_verified_jar_size - position);
    }
    ssize_t n;
    do {
        n = pread(g_verified_jar_fd, buffer, (size_t)count, (off_t)position);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        jclass ex = (*jre)->FindClass(jre, "java/io/IOException");
        if (ex) (*jre)->ThrowNew(jre, ex, "Cannot read verified .ncapp FD");
        return -1;
    }
    if (n > 0) {
        (*jre)->SetByteArrayRegion(jre, target, offset, (jsize)n,
                                  (const jbyte*)buffer);
    }
    return (jint)n;
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
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: JSON result enter");
    if ((*jre)->ExceptionCheck(jre)) return java_error(android_env, jre, "aplicación");
    if (value == NULL) return err(android_env, "interfaz", "La app devolvió null");
    jsize chars = (*jre)->GetStringLength(jre, (jstring)value);
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: JSON length=%d", (int)chars);
    if (chars > MAX_JSON_UTF16) {
        return err(android_env, "interfaz", "JSON demasiado grande");
    }
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: copying HotSpot string to Android");
    const char *data = (*jre)->GetStringUTFChars(jre, (jstring)value, NULL);
    if (data == NULL) return err(android_env, "interfaz", "UTF-8 no disponible");
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: HotSpot UTF chars acquired");
    jstring result = (*android_env)->NewStringUTF(android_env, data);
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: Android string created");
    (*jre)->ReleaseStringUTFChars(jre, (jstring)value, data);
    ncapp_note(ANDROID_LOG_INFO, "JVM bridge: HotSpot UTF chars released");
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
    /* Reuse the verified read-only Binder FD; no filesystem reopening. */
    const char *prefix = "/proc/self/fd/";
    if (strncmp(archive, prefix, strlen(prefix)) != 0) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "Invalid FD descriptor");
    }
    char *tail = NULL;
    errno = 0;
    long source_fd = strtol(archive + strlen(prefix), &tail, 10);
    if (errno != 0 || tail == archive + strlen(prefix) || *tail != '\0'
            || source_fd < 0 || source_fd > INT_MAX) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "Invalid FD number");
    }
    struct stat jar_stat;
    int access_mode = fcntl((int)source_fd, F_GETFL);
    if (fstat((int)source_fd, &jar_stat) != 0
            || !S_ISREG(jar_stat.st_mode)
            || jar_stat.st_size <= 0 || jar_stat.st_size > MAX_NCAPP_BYTES
            || access_mode < 0 || (access_mode & O_ACCMODE) != O_RDONLY) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "Read-only Binder archive FD is invalid");
    }
    g_verified_jar_fd = dup((int)source_fd);
    if (g_verified_jar_fd < 0) {
        (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
        (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
        (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
        (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
        return err(android_env, "jar", "Could not duplicate verified archive FD");
    }
    g_verified_jar_size = (jlong)jar_stat.st_size;
    snprintf(appOption, sizeof appOption, "-Djava.class.path=");
    ncapp_note(ANDROID_LOG_INFO,
               "JVM classpath: streaming %lld-byte archive via isolated Binder FD",
               (long long)g_verified_jar_size);
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
         ,{.optionString = "-Xlog:gc=info"}
        ,{.optionString = "-XX:+UseSerialGC"}
#endif
    };
    JavaVMInitArgs args = {
        .version = JNI_VERSION_1_6,
        .nOptions = sizeof options / sizeof options[0],
        .options = options,
        .ignoreUnrecognized = JNI_FALSE
    };
    JNIEnv *jre = NULL;
#ifdef NEUROCENTER_DEBUG_DIAGNOSTICS
    ncapp_note(ANDROID_LOG_INFO,
               "DIAGNÓSTICO GC: Serial GC activado; G1 desactivado solo en DEBUG (sin modificar etiquetas nativas)");
#endif
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 4: entrando en JNI_CreateJavaVM");
    jint status = create(&g_vm, (void**)&jre, &args);
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 5: JNI_CreateJavaVM retornó %d", status);
    if (status != JNI_OK || !g_vm || !jre) {
        char code[64];
        g_vm = NULL;
        snprintf(code, sizeof code, "JNI_CreateJavaVM devolvió %d", status);
        return err(android_env, "arranque", code);
    }

    ncapp_note(ANDROID_LOG_INFO, "JVM stage 6: preparando cargador JAR por Binder FD");
    jclass loaderClass = (*jre)->DefineClass(jre,
            "com/neurocenter/sandbox/StreamingJarClassLoader", NULL,
            (const jbyte*)kStreamingJarLoaderClass,
            (jsize)sizeof(kStreamingJarLoaderClass));
    if (!loaderClass || (*jre)->ExceptionCheck(jre)) {
        return java_error(android_env, jre, "cargador JAR");
    }
    jclass nestedInput = (*jre)->DefineClass(jre,
            "com/neurocenter/sandbox/StreamingJarClassLoader$ArchiveInput", NULL,
            (const jbyte*)kStreamingJarArchiveInputClass,
            (jsize)sizeof(kStreamingJarArchiveInputClass));
    if (!nestedInput || (*jre)->ExceptionCheck(jre)) {
        return java_error(android_env, jre, "lector JAR");
    }
    JNINativeMethod bridge[] = {
        {"readArchive", "([BIIJ)I", (void*)read_archive_for_hotspot}
    };
    if ((*jre)->RegisterNatives(jre, loaderClass, bridge, 1) != JNI_OK) {
        return java_error(android_env, jre, "registro del lector JAR");
    }
    jmethodID loaderCtor = (*jre)->GetMethodID(jre, loaderClass, "<init>", "(J)V");
    if (!loaderCtor) return java_error(android_env, jre, "constructor del cargador");
    jobject jarLoader = (*jre)->NewObject(jre, loaderClass, loaderCtor,
                                          g_verified_jar_size);
    if (!jarLoader || (*jre)->ExceptionCheck(jre)) {
        return java_error(android_env, jre, "cargador JAR");
    }
    g_jar_loader = (*jre)->NewGlobalRef(jre, jarLoader);
    if (!g_jar_loader) return err(android_env, "memoria", "Sin memoria para cargador JAR");
    jclass parentClass = (*jre)->FindClass(jre, "java/lang/ClassLoader");
    if (!parentClass) return java_error(android_env, jre, "ClassLoader de Java 21");
    jmethodID loadMethod = (*jre)->GetMethodID(jre, parentClass, "loadClass",
                                              "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadMethod) return java_error(android_env, jre, "ClassLoader.loadClass");
    /* JNI strings from Android ART have already been released at this point.
       Use our retained validated className rather than a dangling pointer. */
    char dottedName[sizeof className];
    strcpy(dottedName, className);
    for (char *p = dottedName; *p; p++) if (*p == '/') *p = '.';
    jstring javaName = (*jre)->NewStringUTF(jre, dottedName);
    if (!javaName) return java_error(android_env, jre, "nombre de clase");
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 6a: leyendo clase móvil desde Binder FD");
    jclass clazz = (jclass)(*jre)->CallObjectMethod(jre, g_jar_loader,
                                                   loadMethod, javaName);
    if (!clazz || (*jre)->ExceptionCheck(jre)) {
        return java_error(android_env, jre, "clase móvil");
    }
    ncapp_note(ANDROID_LOG_INFO, "JVM stage 6b: clase móvil cargada");
    /* Resource loading / ServiceLoader should use the isolated ncapp loader. */
    jclass threadClass = (*jre)->FindClass(jre, "java/lang/Thread");
    if (!threadClass) return java_error(android_env, jre, "Thread class");
    jmethodID currentThread = (*jre)->GetStaticMethodID(jre, threadClass,
                              "currentThread", "()Ljava/lang/Thread;");
    jmethodID setContextLoader = (*jre)->GetMethodID(jre, threadClass,
                              "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
    if (!currentThread || !setContextLoader) {
        return java_error(android_env, jre, "thread context loader");
    }
    jobject thread = (*jre)->CallStaticObjectMethod(jre, threadClass, currentThread);
    if (!thread) return java_error(android_env, jre, "currentThread");
    (*jre)->CallVoidMethod(jre, thread, setContextLoader, g_jar_loader);
    if ((*jre)->ExceptionCheck(jre)) {
        return java_error(android_env, jre, "thread context loader");
    }
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
    ncapp_note(ANDROID_LOG_INFO, "JVM action: calling onAction");
    jobject value = (*jre)->CallObjectMethod(jre, g_app, g_action, javaAction);
    ncapp_note(ANDROID_LOG_INFO, "JVM action: onAction returned");
    jstring result = json_result(android_env, jre, value);
    ncapp_note(ANDROID_LOG_INFO, "JVM action: response converted for Android");
    if (attached) {
        ncapp_note(ANDROID_LOG_INFO, "JVM action: detaching HotSpot thread");
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    ncapp_note(ANDROID_LOG_INFO, "JVM action: JNI method completed");
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
    ncapp_note(ANDROID_LOG_INFO, "JVM host response: calling onHostResponse");
    jobject screen = (*jre)->CallObjectMethod(jre, g_app, method, jcap, jresponse);
    ncapp_note(ANDROID_LOG_INFO, "JVM host response: onHostResponse returned");
    jstring result = json_result(android_env, jre, screen);
    ncapp_note(ANDROID_LOG_INFO, "JVM host response: response converted for Android");
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return result;
}
