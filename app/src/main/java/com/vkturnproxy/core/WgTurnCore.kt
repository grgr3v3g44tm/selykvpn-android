package com.vkturnproxy.core

import android.util.Log

object WgTurnCore {
    private const val TAG = "WgTurnCore"

    init {
        try {
            System.loadLibrary("wgturn")
            Log.i(TAG, "libwgturn.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libwgturn.so", e)
        }
    }

    external fun registerSocketProtector(vpnService: Any)

    external fun startVKBootstrap(proxyConfigJson: String): Int
    external fun waitBootstrapReady(handle: Int, timeoutMs: Int): Int
    external fun attachWireGuard(handle: Int, wgConfig: String, tunFd: Int): Int
    external fun turnOff(handle: Int)

    external fun getStats(handle: Int): String
    external fun waitWrapAProvision(handle: Int, timeoutMs: Int): String
    external fun getTURNServerIP(handle: Int): String

    external fun pause(handle: Int)
    external fun resume(handle: Int)
    external fun wakeHealthCheck(handle: Int)

    external fun solveCaptcha(handle: Int, answer: String)
    external fun refreshCaptchaURL(handle: Int): String
    external fun setLogFilePath(path: String)

    // CSQTT
    external fun csqttStart(proxyConfigJson: String): Int
    external fun csqttWaitReady(handle: Int, timeoutMs: Int): Int
    external fun csqttAttach(handle: Int, tunFd: Int): Int
    external fun csqttTurnOff(handle: Int)
    external fun csqttProvision(handle: Int): String
    external fun csqttGetStats(handle: Int): String

    // VK Cookie Auth & Advanced
    external fun setVKCookieAuth(enabled: Boolean, cookie: String, linksJson: String)
    external fun getAuthError(): String
    external fun setForceLegacyCaptcha(enabled: Boolean)
    external fun setMemstatsFastTicks(enabled: Boolean)
    external fun setUplinkPace(kib: Int, burstKib: Int)

    // Speedtest
    external fun speedtestServers(): String
    external fun speedtestStart(cfgJson: String): String
    external fun speedtestPoll(): String
    external fun speedtestCancel()
}
