#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

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

static jstring err(JNIEnv *android_env, const char *name, const char *details) {
    char buffer[1024];
    snprintf(buffer, sizeof buffer, "NCAPP_ERROR:%s: %s", name,
             details == NULL ? "desconocido" : details);
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", buffer);
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
    jstring jarchive, jstring jentry, jstring jlibs) {
    (void)ignored;
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 1: entró al lanzador JNI");
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
    snprintf(appOption, sizeof appOption, "-Djava.class.path=%s", archive);
    setenv("JAVA_HOME", home, 1);
    setenv("LD_LIBRARY_PATH", libs, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 2: cargando libjvm.so");
    void *handle = dlopen(libjvm, RTLD_NOW | RTLD_GLOBAL);
    (*android_env)->ReleaseStringUTFChars(android_env, jhome, home);
    (*android_env)->ReleaseStringUTFChars(android_env, jarchive, archive);
    (*android_env)->ReleaseStringUTFChars(android_env, jentry, entry);
    (*android_env)->ReleaseStringUTFChars(android_env, jlibs, libs);
    if (!handle) return err(android_env, "libjvm", dlerror());
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 3: dlopen completado");
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
    };
    JavaVMInitArgs args = {
        .version = JNI_VERSION_1_6,
        .nOptions = sizeof options / sizeof options[0],
        .options = options,
        .ignoreUnrecognized = JNI_FALSE
    };
    JNIEnv *jre = NULL;
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 4: entrando en JNI_CreateJavaVM");
    jint status = create(&g_vm, (void**)&jre, &args);
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 5: JNI_CreateJavaVM retornó %d", status);
    if (status != JNI_OK || !g_vm || !jre) {
        char code[64];
        g_vm = NULL;
        snprintf(code, sizeof code, "JNI_CreateJavaVM devolvió %d", status);
        return err(android_env, "arranque", code);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 6: cargando clase móvil");
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
    __android_log_print(ANDROID_LOG_INFO, TAG, "JVM stage 7: invocando renderJson");
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
