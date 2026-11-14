#include "jni_bridge.h"
#include <pthread.h>

static pthread_mutex_t g_jni_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm = NULL;
static jobject g_vpn_service = NULL;
static jmethodID g_protect_method = NULL;

void go_android_log(const char *msg) {
    __android_log_print(ANDROID_LOG_INFO, "VKTurnProxyGo", "%s", msg);
}

go_vm_stats_t go_get_vm_stats(void) {
    go_vm_stats_t r = {0, 0, 0, 0, 0};
    return r;
}

void set_java_vm(JavaVM *vm) {
    pthread_mutex_lock(&g_jni_lock);
    g_vm = vm;
    pthread_mutex_unlock(&g_jni_lock);
}

void set_vpn_service(JNIEnv *env, jobject service) {
    pthread_mutex_lock(&g_jni_lock);
    JavaVM *vm = g_vm;
    int attached = 0;
    if (!env && vm) {
        jint res = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            if ((*vm)->AttachCurrentThread(vm, &env, NULL) == 0) {
                attached = 1;
            }
        }
    }

    jobject old_service = g_vpn_service;
    g_vpn_service = NULL;
    g_protect_method = NULL;

    if (service != NULL && env != NULL) {
        jobject global_ref = (*env)->NewGlobalRef(env, service);
        jclass clazz = (*env)->GetObjectClass(env, service);
        jmethodID protect_mid = NULL;
        if (clazz) {
            protect_mid = (*env)->GetMethodID(env, clazz, "protect", "(I)Z");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                protect_mid = NULL;
            }
            (*env)->DeleteLocalRef(env, clazz);
        }
        if (global_ref && protect_mid) {
            g_vpn_service = global_ref;
            g_protect_method = protect_mid;
        } else if (global_ref) {
            (*env)->DeleteGlobalRef(env, global_ref);
        }
    }

    if (old_service && env) {
        (*env)->DeleteGlobalRef(env, old_service);
    }

    if (attached && vm) {
        (*vm)->DetachCurrentThread(vm);
    }
    pthread_mutex_unlock(&g_jni_lock);
}

int protect_socket_via_jni(int fd) {
    pthread_mutex_lock(&g_jni_lock);
    JavaVM *vm = g_vm;
    int has_target = (vm != NULL && g_vpn_service != NULL && g_protect_method != NULL);
    pthread_mutex_unlock(&g_jni_lock);
    if (!has_target) return 0;

    JNIEnv *env = NULL;
    int attached = 0;
    jint res = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != 0) return 0;
        attached = 1;
    } else if (res != JNI_OK || !env) {
        return 0;
    }

    pthread_mutex_lock(&g_jni_lock);
    jobject local_service = (g_vpn_service != NULL) ? (*env)->NewLocalRef(env, g_vpn_service) : NULL;
    jmethodID protect_mid = g_protect_method;
    pthread_mutex_unlock(&g_jni_lock);

    if (!local_service || !protect_mid) {
        if (local_service) (*env)->DeleteLocalRef(env, local_service);
        if (attached) (*vm)->DetachCurrentThread(vm);
        return 0;
    }

    jboolean ok = (*env)->CallBooleanMethod(env, local_service, protect_mid, (jint)fd);
    (*env)->DeleteLocalRef(env, local_service);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (attached) {
            (*vm)->DetachCurrentThread(vm);
        }
        return -1;
    }

    if (attached) {
        (*vm)->DetachCurrentThread(vm);
    }
    return ok ? 1 : 0;
}
