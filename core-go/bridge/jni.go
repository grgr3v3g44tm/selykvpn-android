package main

/*
#include <jni.h>
#include <stdlib.h>
#include "include/wireguard_turn.h"

// Forward declaration from bridge.go
extern void set_java_vm(JavaVM *vm);
extern void set_vpn_service(JNIEnv *env, jobject service);

// CSQTT declarations from csqtt_bridge.go
extern int32_t csqttStart(const char *proxyConfigJSON);
extern int32_t csqttWaitReady(int32_t tunnelHandle, int32_t timeoutMs);
extern int32_t csqttAttach(int32_t tunnelHandle, int32_t tunFd);
extern void csqttTurnOff(int32_t tunnelHandle);
extern const char *csqttProvision(int32_t tunnelHandle);
extern const char *csqttGetStats(int32_t tunnelHandle);

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    set_java_vm(vm);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_registerSocketProtector(JNIEnv *env, jclass clazz, jobject vpnService) {
    set_vpn_service(env, vpnService);
}

JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_startVKBootstrap(JNIEnv *env, jclass clazz, jstring jConfig) {
    if (!jConfig) return -1;
    const char *cfg = (*env)->GetStringUTFChars(env, jConfig, NULL);
    int32_t handle = wgStartVKBootstrap(cfg);
    (*env)->ReleaseStringUTFChars(env, jConfig, cfg);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_waitBootstrapReady(JNIEnv *env, jclass clazz, jint handle, jint timeoutMs) {
    return wgWaitBootstrapReady(handle, timeoutMs);
}

JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_attachWireGuard(JNIEnv *env, jclass clazz, jint handle, jstring jWgConfig, jint tunFd) {
    if (!jWgConfig) return -5;
    const char *wgCfg = (*env)->GetStringUTFChars(env, jWgConfig, NULL);
    int32_t res = wgAttachWireGuard(handle, wgCfg, tunFd);
    (*env)->ReleaseStringUTFChars(env, jWgConfig, wgCfg);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_turnOff(JNIEnv *env, jclass clazz, jint handle) {
    wgTurnOff(handle);
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_getStats(JNIEnv *env, jclass clazz, jint handle) {
    const char *stats = wgGetStats(handle);
    if (!stats) return (*env)->NewStringUTF(env, "{}");
    jstring res = (*env)->NewStringUTF(env, stats);
    free((void*)stats);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_waitWrapAProvision(JNIEnv *env, jclass clazz, jint handle, jint timeoutMs) {
    const char *prov = wgWaitWrapAProvision(handle, timeoutMs);
    if (!prov) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, prov);
    free((void*)prov);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_getTURNServerIP(JNIEnv *env, jclass clazz, jint handle) {
    const char *ip = wgGetTURNServerIP(handle);
    if (!ip) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, ip);
    free((void*)ip);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_pause(JNIEnv *env, jclass clazz, jint handle) {
    wgPause(handle);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_resume(JNIEnv *env, jclass clazz, jint handle) {
    wgResume(handle);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_wakeHealthCheck(JNIEnv *env, jclass clazz, jint handle) {
    wgWakeHealthCheck(handle);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_solveCaptcha(JNIEnv *env, jclass clazz, jint handle, jstring jAnswer) {
    if (!jAnswer) return;
    const char *ans = (*env)->GetStringUTFChars(env, jAnswer, NULL);
    wgSolveCaptcha(handle, ans);
    (*env)->ReleaseStringUTFChars(env, jAnswer, ans);
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_refreshCaptchaURL(JNIEnv *env, jclass clazz, jint handle) {
    const char *url = wgRefreshCaptchaURL(handle);
    if (!url) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, url);
    free((void*)url);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_setLogFilePath(JNIEnv *env, jclass clazz, jstring jPath) {
    if (!jPath) return;
    const char *p = (*env)->GetStringUTFChars(env, jPath, NULL);
    wgSetLogFilePath(p);
    (*env)->ReleaseStringUTFChars(env, jPath, p);
}

// CSQTT JNI
JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttStart(JNIEnv *env, jclass clazz, jstring jConfig) {
    if (!jConfig) return -1;
    const char *cfg = (*env)->GetStringUTFChars(env, jConfig, NULL);
    int32_t handle = csqttStart(cfg);
    (*env)->ReleaseStringUTFChars(env, jConfig, cfg);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttWaitReady(JNIEnv *env, jclass clazz, jint handle, jint timeoutMs) {
    return csqttWaitReady(handle, timeoutMs);
}

JNIEXPORT jint JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttAttach(JNIEnv *env, jclass clazz, jint handle, jint tunFd) {
    return csqttAttach(handle, tunFd);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttTurnOff(JNIEnv *env, jclass clazz, jint handle) {
    csqttTurnOff(handle);
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttProvision(JNIEnv *env, jclass clazz, jint handle) {
    const char *p = csqttProvision(handle);
    if (!p) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, p);
    free((void*)p);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_csqttGetStats(JNIEnv *env, jclass clazz, jint handle) {
    const char *s = csqttGetStats(handle);
    if (!s) return (*env)->NewStringUTF(env, "{}");
    jstring res = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_setVKCookieAuth(JNIEnv *env, jclass clazz, jboolean enabled, jstring jCookie, jstring jLinksJson) {
    const char *cookie = jCookie ? (*env)->GetStringUTFChars(env, jCookie, NULL) : "";
    const char *links = jLinksJson ? (*env)->GetStringUTFChars(env, jLinksJson, NULL) : "";
    wgSetVKCookieAuth(enabled ? 1 : 0, cookie, links);
    if (jCookie) (*env)->ReleaseStringUTFChars(env, jCookie, cookie);
    if (jLinksJson) (*env)->ReleaseStringUTFChars(env, jLinksJson, links);
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_getAuthError(JNIEnv *env, jclass clazz) {
    const char *err = wgGetAuthError();
    if (!err) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, err);
    free((void*)err);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_setForceLegacyCaptcha(JNIEnv *env, jclass clazz, jboolean enabled) {
    wgSetForceLegacyCaptcha(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_setMemstatsFastTicks(JNIEnv *env, jclass clazz, jboolean enabled) {
    wgSetMemstatsFastTicks(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_setUplinkPace(JNIEnv *env, jclass clazz, jint kib, jint burstKib) {
    wgSetUplinkPace((int32_t)kib, (int32_t)burstKib);
}

// Speedtest JNI
JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_speedtestServers(JNIEnv *env, jclass clazz) {
    const char *s = wgSpeedtestServers();
    if (!s) return (*env)->NewStringUTF(env, "[]");
    jstring res = (*env)->NewStringUTF(env, s);
    free((void*)s);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_speedtestStart(JNIEnv *env, jclass clazz, jstring jCfg) {
    if (!jCfg) return (*env)->NewStringUTF(env, "missing config");
    const char *cfg = (*env)->GetStringUTFChars(env, jCfg, NULL);
    const char *err = wgSpeedtestStart(cfg);
    (*env)->ReleaseStringUTFChars(env, jCfg, cfg);
    if (!err) return (*env)->NewStringUTF(env, "");
    jstring res = (*env)->NewStringUTF(env, err);
    free((void*)err);
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_vkturnproxy_core_WgTurnCore_speedtestPoll(JNIEnv *env, jclass clazz) {
    const char *snap = wgSpeedtestPoll();
    if (!snap) return (*env)->NewStringUTF(env, "{}");
    jstring res = (*env)->NewStringUTF(env, snap);
    free((void*)snap);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vkturnproxy_core_WgTurnCore_speedtestCancel(JNIEnv *env, jclass clazz) {
    wgSpeedtestCancel();
}
*/
import "C"

// Pure JNI binding file
