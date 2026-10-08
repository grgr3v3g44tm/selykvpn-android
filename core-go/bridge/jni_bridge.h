#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>
#include <jni.h>

void go_android_log(const char *msg);

typedef struct {
    uint64_t phys_footprint;
    uint64_t internal;
    uint64_t external;
    uint64_t reusable;
    uint64_t compressed;
} go_vm_stats_t;

go_vm_stats_t go_get_vm_stats(void);

void set_java_vm(JavaVM *vm);
void set_vpn_service(JNIEnv *env, jobject service);
int protect_socket_via_jni(int fd);

#endif // JNI_BRIDGE_H
