#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOGTAG "NeuroCenter-JVM21"
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))

typedef jint(JNICALL *create_java_vm_t)(JavaVM **, void **, void *);

static jstring fail(JNIEnv *env, const char *where, const char *details) {
    char buf[512];
    snprintf(buf, sizeof(buf), "OpenJDK 21 %s: %s", where,
             details == NULL ? "error desconocido" : details);
    __android_log_print(ANDROID_LOG_ERROR, LOGTAG, "%s", buf);
    return (*env)->NewStringUTF(env, buf);
}

/* Diagnostic only: NEVER executes an untrusted .ncapp in this host-UID process. */
JNIEXPORT jstring JNICALL
Java_com_neurocenter_core_Java21Runtime_probeNative(
        JNIEnv *art, jclass clazz, jstring jre_home, jstring library_dir) {
    (void) clazz;
    if (jre_home == NULL || library_dir == NULL) {
        return fail(art, "configuración", "faltan rutas");
    }
    const char *home = (*art)->GetStringUTFChars(art, jre_home, NULL);
    if (home == NULL) return NULL;
    const char *libs = (*art)->GetStringUTFChars(art, library_dir, NULL);
    if (libs == NULL) {
        (*art)->ReleaseStringUTFChars(art, jre_home, home);
        return NULL;
    }

    char jvm_path[1024];
    char opt_home[1200];
    char opt_lib[1200];
    snprintf(jvm_path, sizeof(jvm_path), "%s/libjvm.so", libs);
    snprintf(opt_home, sizeof(opt_home), "-Djava.home=%s", home);
    snprintf(opt_lib, sizeof(opt_lib), "-Djava.library.path=%s", libs);
    setenv("JAVA_HOME", home, 1);
    setenv("LD_LIBRARY_PATH", libs, 1);

    void *handle = dlopen(jvm_path, RTLD_NOW | RTLD_GLOBAL);
    (*art)->ReleaseStringUTFChars(art, jre_home, home);
    (*art)->ReleaseStringUTFChars(art, library_dir, libs);
    if (handle == NULL) return fail(art, "dlopen(libjvm.so)", dlerror());

    create_java_vm_t create_vm = (create_java_vm_t)dlsym(handle, "JNI_CreateJavaVM");
    if (create_vm == NULL) return fail(art, "símbolo", "JNI_CreateJavaVM no encontrado");

    JavaVMOption opts[] = {
        {.optionString = opt_home},
        {.optionString = opt_lib},
        {.optionString = "-Djava.awt.headless=true"},
        {.optionString = "-Djava.security.manager=allow"},
        {.optionString = "-Djdk.lang.Process.launchMechanism=FORK"},
        {.optionString = "-Xmx128m"}
    };
    JavaVMInitArgs args = {
        .version = JNI_VERSION_1_6,
        .nOptions = (jint)ARRAY_SIZE(opts),
        .options = opts,
        .ignoreUnrecognized = JNI_FALSE
    };
    JavaVM *vm = NULL;
    JNIEnv *jre = NULL;
    jint status = create_vm(&vm, (void **)&jre, &args);
    if (status != JNI_OK || vm == NULL || jre == NULL) {
        char code[64];
        snprintf(code, sizeof(code), "JNI_CreateJavaVM devolvió %d", status);
        return fail(art, "arranque", code);
    }

    /* Only java.lang.System from the VM's own module image is called.
       The app bytecode is not loaded into this diagnostic process. */
    jclass system_class = (*jre)->FindClass(jre, "java/lang/System");
    if (system_class == NULL) {
        if ((*jre)->ExceptionCheck(jre)) (*jre)->ExceptionDescribe(jre);
        return fail(art, "verificación", "java.lang.System no disponible");
    }
    jmethodID property = (*jre)->GetStaticMethodID(jre, system_class, "getProperty",
                                                   "(Ljava/lang/String;)Ljava/lang/String;");
    if (property == NULL) {
        if ((*jre)->ExceptionCheck(jre)) (*jre)->ExceptionDescribe(jre);
        return fail(art, "verificación", "System.getProperty no disponible");
    }
    jstring key = (*jre)->NewStringUTF(jre, "java.version");
    jstring version = (jstring)(*jre)->CallStaticObjectMethod(jre, system_class, property, key);
    if ((*jre)->ExceptionCheck(jre)) {
        (*jre)->ExceptionDescribe(jre);
        return fail(art, "verificación", "falló la consulta de versión");
    }
    if (version == NULL) return fail(art, "verificación", "versión no disponible");
    const char *value = (*jre)->GetStringUTFChars(jre, version, NULL);
    if (value == NULL) return fail(art, "verificación", "no se pudo leer la versión");
    char response[256];
    snprintf(response, sizeof(response), "HotSpot OpenJDK %s (VM nativa integrada)", value);
    (*jre)->ReleaseStringUTFChars(jre, version, value);
    if (strncmp(response, "HotSpot OpenJDK 21", 17) != 0) {
        return fail(art, "versión", response);
    }
    return (*art)->NewStringUTF(art, response);
}
