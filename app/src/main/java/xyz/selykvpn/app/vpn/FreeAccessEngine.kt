package xyz.selykvpn.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vkturnproxy.core.WgTurnCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode

class FreeAccessEngine(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "FreeAccessEngine"
    }

    private var tunnelHandle: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun start(config: FreeAccessConfig, excludedApps: Set<String>, onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                Log.i(TAG, "Starting Free Access tunnel...")
                WgTurnCore.registerSocketProtector(vpnService)

                val proxyConfigJson = config.toProxyConfigJson()
                val handle = WgTurnCore.startVKBootstrap(proxyConfigJson)
                if (handle <= 0) {
                    Log.e(TAG, "Failed to start bootstrap, handle=$handle")
                    onError("Ошибка инициализации шлюза")
                    return@launch
                }
                tunnelHandle = handle

                // Wait for TURN allocation & readiness
                val ready = WgTurnCore.waitBootstrapReady(handle, 60_000)
                if (ready != 1) {
                    Log.e(TAG, "Bootstrap failed or timed out: ready=$ready")
                    stop()
                    onError("Таймаут подключения к прямому шлюзу")
                    return@launch
                }

                var uapiConfig = config.toUAPIConfig()
                var tunnelAddr = config.tunnelAddress
                var dnsServers = config.dnsServers
                var mtu = config.mtu

                if (config.mode == TunnelMode.SRTP_WRAP_A) {
                    if (config.serverPassword.isBlank()) {
                        Log.e(TAG, "WRAP-A mode requires serverPassword")
                        stop()
                        onError("В ключе отсутствует пароль сервера WRAP-A. Обновите ключ в Telegram-боте.")
                        return@launch
                    }
                    val provJson = WgTurnCore.waitWrapAProvision(handle, 15_000)
                    if (provJson.isNotBlank()) {
                        try {
                            val element = Json.parseToJsonElement(provJson).jsonObject
                            element["uapi"]?.jsonPrimitive?.content?.let { uapiConfig = it }
                            element["address"]?.jsonPrimitive?.content?.let { tunnelAddr = it }
                            element["dns"]?.jsonPrimitive?.content?.let { dnsServers = it }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing WRAP-A provision: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "WRAP-A provision failed")
                        stop()
                        onError("Ошибка авторизации устройства")
                        return@launch
                    }
                }

                val builder = vpnService.Builder()
                    .setSession("Свободный доступ")
                    .setMtu(mtu.coerceIn(1200, 1280))
                    .setBlocking(false)

                val addrParts = tunnelAddr.split("/")
                val ip = addrParts[0].trim()
                val prefix = addrParts.getOrNull(1)?.toIntOrNull() ?: 24
                builder.addAddress(ip, prefix)
                builder.addRoute("0.0.0.0", 0)

                // Trap IPv6 to prevent leaks on mobile networks
                try {
                    builder.addAddress("fd00:77:77::2", 64)
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to configure IPv6 route: ${e.message}")
                }

                for (dns in dnsServers.split(",")) {
                    val trimmed = dns.trim()
                    if (trimmed.isNotEmpty()) {
                        builder.addDnsServer(trimmed)
                    }
                }

                // Split tunneling
                for (pkg in excludedApps) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Exception) {}
                }
                // App itself bypasses VPN
                try {
                    builder.addDisallowedApplication(vpnService.packageName)
                } catch (_: Exception) {}

                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "VpnService.Builder.establish() returned null")
                    stop()
                    onError("Не удалось создать сетевой интерфейс")
                    return@launch
                }
                tunPfd = pfd

                val attachRes = WgTurnCore.attachWireGuard(handle, uapiConfig, pfd.fd)
                if (attachRes != 0) {
                    Log.e(TAG, "attachWireGuard failed with code $attachRes")
                    stop()
                    onError("Ошибка связывания сетевого туннеля ($attachRes)")
                    return@launch
                }

                Log.i(TAG, "Free Access tunnel attached successfully!")
                _isConnected.value = true
                onReady()

                startStatsCollection()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Free Access tunnel", e)
                stop()
                onError(e.message ?: "Ошибка подключения")
            }
        }
    }

    private fun startStatsCollection() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastDown = 0L
            var lastUp = 0L
            while (isActive && _isConnected.value && tunnelHandle > 0) {
                delay(1000)
                try {
                    val statsStr = WgTurnCore.getStats(tunnelHandle)
                    if (statsStr.isNotBlank()) {
                        val parts = statsStr.split(",")
                        val downTotal = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        val upTotal = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val downSpeed = (downTotal - lastDown).coerceAtLeast(0)
                        val upSpeed = (upTotal - lastUp).coerceAtLeast(0)
                        lastDown = downTotal
                        lastUp = upTotal
                        VpnController.traffic.value = VpnController.Traffic(
                            up = upSpeed,
                            down = downSpeed,
                            totalUp = upTotal,
                            totalDown = downTotal
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        _isConnected.value = false
        statsJob?.cancel()
        statsJob = null

        val h = tunnelHandle
        tunnelHandle = -1
        if (h > 0) {
            try {
                WgTurnCore.turnOff(h)
            } catch (e: Exception) {
                Log.w(TAG, "Error in turnOff", e)
            }
        }

        try {
            tunPfd?.close()
        } catch (_: Exception) {}
        tunPfd = null
    }
}
