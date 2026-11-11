package xyz.selykvpn.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vkturnproxy.core.WgTurnCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode

@Serializable
private data class FreeAccessStatsDto(
    @SerialName("rx_bytes") val rxBytes: Long = 0,
    @SerialName("tx_bytes") val txBytes: Long = 0,
)

class FreeAccessEngine(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "FreeAccessEngine"
        private const val BOOTSTRAP_TIMEOUT_MS = 120_000
        private const val WRAP_A_TIMEOUT_MS = 30_000
        private const val HANDSHAKE_TIMEOUT_SEC = 12
    }

    @Volatile
    private var tunnelHandle: Int = -1

    @Volatile
    private var tunPfd: ParcelFileDescriptor? = null

    @Volatile
    private var startGen: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    private var startJob: Job? = null
    private var statsJob: Job? = null
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun start(
        config: FreeAccessConfig,
        excludedApps: Set<String>,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val prevJob = startJob
        val myGen = synchronized(this) {
            stopRequested = false
            ++startGen
        }
        startJob = scope.launch {
            lifecycleMutex.withLock {
                if (stopRequested || startGen != myGen || !isActive) return@withLock

                // Stop any previous tunnel cleanly before launching a new one
                prevJob?.cancel()
                tearDownNative()

                fun isAborted(): Boolean = stopRequested || startGen != myGen || !coroutineContext.isActive

                try {
                    VKCookieStore.init(vpnService.applicationContext)

                    // Pre-flight validation before starting TURN bootstrap
                    if (!config.hasRealVKCallLink()) {
                        Log.e(TAG, "Missing valid VK call link")
                        onError("Требуется ссылка на звонок VK. Откройте «Прямой защищённый шлюз» и привяжите звонок.")
                        return@withLock
                    }
                    if (config.mode == TunnelMode.SRTP_WRAP_A && config.serverPassword.isBlank()) {
                        Log.e(TAG, "WRAP-A mode requires serverPassword")
                        onError("В ключе отсутствует пароль сервера WRAP-A. Обновите ключ в Telegram-боте.")
                        return@withLock
                    }
                    if (config.mode != TunnelMode.SRTP_WRAP_A &&
                        (config.privateKey.isBlank() || config.peerPublicKey.isBlank())
                    ) {
                        Log.e(TAG, "WireGuard parameters are missing for mode=${config.mode}")
                        onError("В конфигурации отсутствуют ключи WireGuard. Обновите ключ в Telegram-боте.")
                        return@withLock
                    }

                    Log.i(TAG, "Starting Free Access tunnel: mode=${config.mode}, useUDP=${config.useUDP}")
                    WgTurnCore.registerSocketProtector(vpnService)

                    val cookieLinks = config.getParsedStealthLinks()
                    val validCookie = if (config.useSessionAuth && VKCookieStore.hasValidLogin()) {
                        VKCookieStore.getValidCookieHeader()?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    val effectiveCookieAuth = validCookie != null
                    if (validCookie != null) {
                        val linksJson = AppJson.encodeToString(cookieLinks)
                        WgTurnCore.setVKCookieAuth(true, validCookie, linksJson)
                    } else {
                        WgTurnCore.setVKCookieAuth(false, "", "[]")
                    }

                    WgTurnCore.setUplinkPace(config.uplinkPaceKiB, config.uplinkPaceBurstKiB)
                    WgTurnCore.setForceLegacyCaptcha(config.forceLegacyCaptcha)
                    WgTurnCore.setMemstatsFastTicks(config.memstatsFastTicks)

                    val proxyConfigJson = config.copy(useSessionAuth = effectiveCookieAuth).toProxyConfigJson()
                    val handle = WgTurnCore.startVKBootstrap(proxyConfigJson)
                    if (handle <= 0) {
                        Log.e(TAG, "Failed to start bootstrap, handle=$handle")
                        if (!isAborted()) onError("Ошибка инициализации шлюза")
                        return@withLock
                    }
                    tunnelHandle = handle
                    if (isAborted()) {
                        tearDownNative()
                        return@withLock
                    }

                    val ready = WgTurnCore.waitBootstrapReady(handle, BOOTSTRAP_TIMEOUT_MS)
                    if (isAborted()) {
                        tearDownNative()
                        return@withLock
                    }
                    if (ready != 1) {
                        val authError = runCatching { WgTurnCore.getAuthError() }.getOrDefault("")
                        Log.e(TAG, "Bootstrap failed: ready=$ready, authError=$authError")
                        tearDownNative()
                        val userMsg = when {
                            authError.contains("Invalid join link", ignoreCase = true) ||
                                authError.contains("954") ||
                                authError.contains("951") ->
                                "Ссылка на звонок устарела. Создайте новый звонок VK."
                            authError.contains("cookie rejected", ignoreCase = true) ||
                                authError.contains("re-login required", ignoreCase = true) ->
                                "Сессия VK истекла. Войдите заново или выключите «Использовать аккаунт VK»."
                            authError.isNotBlank() -> authError
                            ready == 0 -> "Таймаут подключения к прямому шлюзу"
                            else -> "Ошибка подключения к серверам VK ($ready)"
                        }
                        onError(userMsg)
                        return@withLock
                    }

                    var uapiConfig = config.toUAPIConfig()
                    var tunnelAddr = config.tunnelAddress
                    var dnsServers = config.dnsServers
                    var mtu = config.mtu

                    if (config.mode == TunnelMode.SRTP_WRAP_A) {
                        val provJson = WgTurnCore.waitWrapAProvision(handle, WRAP_A_TIMEOUT_MS)
                        if (isAborted()) {
                            tearDownNative()
                            return@withLock
                        }
                        if (provJson.isNotBlank()) {
                            try {
                                val element = Json.parseToJsonElement(provJson).jsonObject
                                element["uapi"]?.jsonPrimitive?.content?.let { uapiConfig = it }
                                element["address"]?.jsonPrimitive?.content?.let { tunnelAddr = it }
                                element["dns"]?.jsonPrimitive?.content?.let { dnsServers = it }
                                element["mtu"]?.jsonPrimitive?.content?.toIntOrNull()?.let { provMtu ->
                                    if (provMtu in 1200..1280) mtu = provMtu
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing WRAP-A provision: ${e.message}")
                            }
                        } else {
                            val authError = runCatching { WgTurnCore.getAuthError() }.getOrDefault("")
                            Log.e(TAG, "WRAP-A provision failed: authError=$authError")
                            tearDownNative()
                            onError(
                                authError.ifBlank {
                                    "Ошибка авторизации устройства WRAP-A. Проверьте пароль или обновите ключ в Telegram-боте."
                                }
                            )
                            return@withLock
                        }
                    }

                    if (isAborted()) {
                        tearDownNative()
                        return@withLock
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

                    // Trap IPv6 to prevent cellular leaks
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

                    for (pkg in excludedApps) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                    runCatching { builder.addDisallowedApplication(vpnService.packageName) }

                    val pfd = builder.establish()
                    if (pfd == null) {
                        Log.e(TAG, "VpnService.Builder.establish() returned null")
                        tearDownNative()
                        if (!isAborted()) onError("Не удалось создать сетевой интерфейс")
                        return@withLock
                    }
                    tunPfd = pfd

                    if (isAborted()) {
                        tearDownNative()
                        return@withLock
                    }

                    val attachRes = WgTurnCore.attachWireGuard(handle, uapiConfig, pfd.fd)
                    if (attachRes < 0) {
                        Log.e(TAG, "attachWireGuard failed with code $attachRes")
                        tearDownNative()
                        if (!isAborted()) onError("Ошибка связывания сетевого туннеля ($attachRes)")
                        return@withLock
                    }

                    Log.i(TAG, "TUN and WireGuard attached, waiting for handshake...")
                    startStatsAndHandshake(handle, myGen, onReady, onError)
                } catch (_: CancellationException) {
                    tearDownNative()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting Free Access tunnel", e)
                    tearDownNative()
                    if (!isAborted()) onError(e.message ?: "Ошибка подключения")
                }
            }
        }
    }

    private fun startStatsAndHandshake(
        handle: Int,
        expectedGen: Long,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        statsJob?.cancel()
        VpnController.traffic.value = VpnController.Traffic.ZERO
        statsJob = scope.launch {
            var lastDown = 0L
            var lastUp = 0L
            var handshakeConfirmed = false
            var handshakeWaitSec = 0

            while (isActive && !stopRequested && startGen == expectedGen && tunnelHandle == handle) {
                try {
                    val statsJson = WgTurnCore.getStats(handle)
                    if (statsJson.isNotBlank() && statsJson != "{}") {
                        val parsed = AppJson.decodeFromString<FreeAccessStatsDto>(statsJson)
                        val downTotal = parsed.rxBytes
                        val upTotal = parsed.txBytes

                        if (!handshakeConfirmed) {
                            if (downTotal > 0) {
                                handshakeConfirmed = true
                                _isConnected.value = true
                                Log.i(TAG, "WireGuard handshake confirmed (rx_bytes=$downTotal)")
                                onReady()
                            } else {
                                handshakeWaitSec++
                                if (handshakeWaitSec >= HANDSHAKE_TIMEOUT_SEC) {
                                    Log.e(TAG, "WireGuard handshake timed out (rx_bytes == 0 after ${HANDSHAKE_TIMEOUT_SEC}s)")
                                    tearDownNative()
                                    if (!stopRequested && startGen == expectedGen) {
                                        onError("Сервер отклонил ключ авторизации. Пожалуйста, обновите ключ в Telegram-боте.")
                                    }
                                    return@launch
                                }
                            }
                        }

                        val downSpeed = if (lastDown > 0) (downTotal - lastDown).coerceAtLeast(0) else 0L
                        val upSpeed = if (lastUp > 0) (upTotal - lastUp).coerceAtLeast(0) else 0L
                        lastDown = downTotal
                        lastUp = upTotal
                        VpnController.traffic.value = VpnController.Traffic(
                            up = upSpeed,
                            down = downSpeed,
                            totalUp = upTotal,
                            totalDown = downTotal
                        )
                    } else if (!handshakeConfirmed) {
                        handshakeWaitSec++
                        if (handshakeWaitSec >= HANDSHAKE_TIMEOUT_SEC) {
                            Log.e(TAG, "WireGuard handshake timed out (empty stats after ${HANDSHAKE_TIMEOUT_SEC}s)")
                            tearDownNative()
                            if (!stopRequested && startGen == expectedGen) {
                                onError("Сервер отклонил ключ авторизации. Пожалуйста, обновите ключ в Telegram-боте.")
                            }
                            return@launch
                        }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private fun tearDownNative() {
        _isConnected.value = false
        statsJob?.cancel()
        statsJob = null

        val h = tunnelHandle
        tunnelHandle = -1
        if (h > 0) {
            runCatching { WgTurnCore.turnOff(h) }
                .onFailure { Log.w(TAG, "Error in turnOff", it) }
        }

        runCatching { tunPfd?.close() }
        tunPfd = null
        runCatching { WgTurnCore.registerSocketProtector(null) }
    }

    fun stop() {
        synchronized(this) {
            stopRequested = true
            startGen++
        }
        startJob?.cancel()
        startJob = null
        tearDownNative()
        VpnController.traffic.value = VpnController.Traffic.ZERO
    }
}
