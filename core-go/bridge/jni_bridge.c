#include "jni_bridge.h"

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
    g_vm = vm;
}

void set_vpn_service(JNIEnv *env, jobject service) {
    int attached = 0;
    if (!env && g_vm) {
        jint res = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == 0) {
                attached = 1;
            }
        }
    }

    if (g_vpn_service) {
        if (env) {
            (*env)->DeleteGlobalRef(env, g_vpn_service);
        }
        g_vpn_service = NULL;
        g_protect_method = NULL;
    }

    if (service != NULL && env != NULL) {
        g_vpn_service = (*env)->NewGlobalRef(env, service);
        jclass clazz = (*env)->GetObjectClass(env, service);
        if (clazz) {
            g_protect_method = (*env)->GetMethodID(env, clazz, "protect", "(I)Z");
            (*env)->DeleteLocalRef(env, clazz);
        }
    }

    if (attached && g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

int protect_socket_via_jni(int fd) {
    if (!g_vm || !g_vpn_service || !g_protect_method) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    jint res = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return 0;
        attached = 1;
    } else if (res != JNI_OK || !env) {
        return 0;
    }

    jboolean ok = (*env)->CallBooleanMethod(env, g_vpn_service, g_protect_method, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (attached) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return -1;
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ok ? 1 : 0;
}
