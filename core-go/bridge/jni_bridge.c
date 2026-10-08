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
    if (g_vpn_service && env) {
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
        g_protect_method = NULL;
    }
    if (service != NULL && env != NULL) {
        g_vpn_service = (*env)->NewGlobalRef(env, service);
        jclass clazz = (*env)->GetObjectClass(env, service);
        g_protect_method = (*env)->GetMethodID(env, clazz, "protect", "(I)Z");
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
    }
    jboolean ok = (*env)->CallBooleanMethod(env, g_vpn_service, g_protect_method, (jint)fd);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ok ? 1 : 0;
}
