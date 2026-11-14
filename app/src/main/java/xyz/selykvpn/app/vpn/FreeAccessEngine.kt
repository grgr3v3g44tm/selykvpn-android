package xyz.selykvpn.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.vkturnproxy.core.WgTurnCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode

class FreeAccessEngine(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "FreeAccessEngine"
        private const val BOOTSTRAP_TIMEOUT_MS = 75_000L
        private const val CAPTCHA_TIMEOUT_MS = 180_000L
        private const val WRAP_A_TIMEOUT_MS = 25_000L
        private const val POLL_STEP_MS = 1_000
        private const val HANDSHAKE_TIMEOUT_SEC = 15

        internal fun mapBootstrapError(readyCode: Int, authError: String): String {
            val cleanedError = if (authError.startsWith("bootstrap timeout after", ignoreCase = true)) {
                ""
            } else {
                authError
            }
            return when {
                cleanedError.contains("Invalid join link", ignoreCase = true) ||
                    cleanedError.contains("954") ||
                    cleanedError.contains("951") ->
                    "Ссылка на звонок устарела. Создайте новый звонок VK."
                cleanedError.contains("cookie rejected", ignoreCase = true) ||
                    cleanedError.contains("re-login required", ignoreCase = true) ->
                    "Сессия VK истекла. Войдите через VK заново или вставьте ссылку вручную."
                cleanedError.contains("WRAP-A DTLS", ignoreCase = true) ||
                    cleanedError.contains("DTLS handshake", ignoreCase = true) ->
                    "Шлюз Свободного доступа не отвечает на рукопожатие (DTLS timeout). Проверьте ключ в боте или попробуйте позже."
                cleanedError.isNotBlank() -> cleanedError
                readyCode == 0 -> "Таймаут подключения к прямому шлюзу"
                else -> "Ошибка подключения к серверам VK ($readyCode)"
            }
        }
    }

    private data class NativeStats(
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
        val captchaImageUrl: String = "",
        val captchaSid: String = "",
        val authError: String = "",
    )

    private sealed interface BootstrapResult {
        data object Ready : BootstrapResult
        data object Aborted : BootstrapResult
        data object Timeout : BootstrapResult
        data class Failed(val code: Int) : BootstrapResult
    }

    private val nativeLock = Any()
    private val nativeOpLock = Any()

    @Volatile
    private var tunnelHandle: Int = -1

    @Volatile
    private var tunPfd: ParcelFileDescriptor? = null

    @Volatile
    private var startGen: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var loggingEnabled: Boolean = false

    private var startJob: Job? = null
    private var statsJob: Job? = null
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun setLogEnabled(enabled: Boolean) {
        loggingEnabled = enabled
        runCatching {
            WgTurnCore.setCacheDir(FreeAccessLog.logDir(vpnService).absolutePath)
            if (enabled) {
                val logFile = FreeAccessLog.prepareForSession(vpnService)
                WgTurnCore.setLogFilePath(logFile.absolutePath)
            } else {
                WgTurnCore.setLogFilePath("")
            }
        }.onFailure { Log.w(TAG, "setLogEnabled($enabled) failed: ${it.message}") }
    }

    private fun logDiagnostic(message: String) {
        if (loggingEnabled) FreeAccessLog.appendLine(vpnService, TAG, message)
    }

    fun solveCaptcha(answer: String): Boolean {
        val handle = synchronized(nativeLock) { tunnelHandle }
        if (handle <= 0) return false
        logDiagnostic("Submitting VK captcha answer")
        return runCatching {
            WgTurnCore.solveCaptcha(handle, answer)
            true
        }.getOrDefault(false)
    }

    fun refreshCaptchaUrl(): String? {
        val handle = synchronized(nativeLock) { tunnelHandle }
        if (handle <= 0) return null
        logDiagnostic("Refreshing VK captcha image")
        return runCatching { WgTurnCore.refreshCaptchaURL(handle).takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun start(
        config: FreeAccessConfig,
        validCookie: String?,
        excludedApps: Set<String>,
        logEnabled: Boolean = false,
        onStage: (String) -> Unit,
        onCaptcha: (imageUrl: String, sid: String) -> Unit,
        onReady: () -> Unit,
        onTraffic: (VpnController.Traffic) -> Unit,
        onError: (String) -> Unit,
    ) {
        loggingEnabled = logEnabled
        val prevJob = startJob
        val myGen = synchronized(nativeLock) {
            stopRequested = false
            ++startGen
        }
        // Cancel and tear down native resources BEFORE acquiring lifecycleMutex so an in-progress
        // bootstrap or WRAP-A wait aborts immediately instead of blocking the next start attempt.
        prevJob?.cancel()
        tearDownNative()

        startJob = scope.launch {
            lifecycleMutex.withLock {
                if (stopRequested || startGen != myGen || !isActive) return@withLock
                tearDownNative()

                fun isAborted(): Boolean = stopRequested || startGen != myGen || !coroutineContext.isActive

                try {
                    if (!config.hasRealVKCallLink()) {
                        Log.e(TAG, "Missing valid VK call link")
                        logDiagnostic("Aborted: missing valid VK call link")
                        onError("Требуется ссылка на звонок VK. Откройте «Прямой защищённый шлюз» и привяжите звонок.")
                        return@withLock
                    }
                    if (config.mode == TunnelMode.SRTP_WRAP_A && config.serverPassword.isBlank()) {
                        Log.e(TAG, "WRAP-A mode requires serverPassword")
                        logDiagnostic("Aborted: WRAP-A serverPassword missing")
                        onError("В ключе отсутствует пароль сервера WRAP-A. Обновите ключ в Telegram-боте.")
                        return@withLock
                    }
                    if (config.mode != TunnelMode.SRTP_WRAP_A &&
                        (config.privateKey.isBlank() || config.peerPublicKey.isBlank())
                    ) {
                        Log.e(TAG, "WireGuard parameters are missing for mode=${config.mode}")
                        logDiagnostic("Aborted: WireGuard keys missing")
                        onError("В конфигурации отсутствуют ключи WireGuard. Обновите ключ в Telegram-боте.")
                        return@withLock
                    }

                    setLogEnabled(loggingEnabled)

                    val cookieLinks = config.getParsedStealthLinks()
                    val cookieToUse = validCookie?.takeIf { config.useSessionAuth && it.isNotBlank() }
                    val effectiveCookieAuth = cookieToUse != null
                    logDiagnostic(
                        "Start mode=${config.mode}, useUDP=${config.useUDP}, cookieAuth=$effectiveCookieAuth, peer=${config.peerAddress}"
                    )
                    Log.i(TAG, "Starting Free Access tunnel: mode=${config.mode}, useUDP=${config.useUDP}")
                    WgTurnCore.registerSocketProtector(vpnService)

                    if (cookieToUse != null) {
                        val linksJson = AppJson.encodeToString(cookieLinks)
                        WgTurnCore.setVKCookieAuth(true, cookieToUse, linksJson)
                    } else {
                        WgTurnCore.setVKCookieAuth(false, "", "[]")
                    }

                    WgTurnCore.setUplinkPace(config.uplinkPaceKiB, config.uplinkPaceBurstKiB)
                    WgTurnCore.setForceLegacyCaptcha(config.forceLegacyCaptcha)
                    WgTurnCore.setMemstatsFastTicks(config.memstatsFastTicks)

                    onStage(vpnService.getString(R.string.free_access_stage_vk))

                    val proxyConfigJson = config.copy(useSessionAuth = effectiveCookieAuth).toProxyConfigJson()
                    val handle = WgTurnCore.startVKBootstrap(proxyConfigJson)
                    if (handle <= 0) {
                        Log.e(TAG, "Failed to start bootstrap, handle=$handle")
                        logDiagnostic("startVKBootstrap failed: handle=$handle")
                        if (!isAborted()) onError("Ошибка инициализации шлюза")
                        return@withLock
                    }

                    val claimed = synchronized(nativeLock) {
                        if (isAborted()) false else {
                            tunnelHandle = handle
                            true
                        }
                    }
                    if (!claimed) {
                        runCatching { WgTurnCore.turnOff(handle) }
                        return@withLock
                    }

                    val bootstrapResult = awaitBootstrapReady(handle, ::isAborted, onStage, onCaptcha)
                    if (bootstrapResult is BootstrapResult.Aborted || isAborted()) {
                        onCaptcha("", "")
                        tearDownNative()
                        return@withLock
                    }
                    if (bootstrapResult !is BootstrapResult.Ready) {
                        onCaptcha("", "")
                        val authError = combineAuthErrors(handle)
                        val code = (bootstrapResult as? BootstrapResult.Failed)?.code ?: 0
                        Log.e(TAG, "Bootstrap failed: result=$bootstrapResult, authError=$authError")
                        logDiagnostic("Bootstrap failed: result=$bootstrapResult authError=$authError")
                        if (bootstrapResult is BootstrapResult.Timeout) {
                            FreeAccessLog.clearCredPoolCache(vpnService)
                        }
                        tearDownNative()
                        onError(mapBootstrapError(code, authError))
                        return@withLock
                    }

                    var uapiConfig = config.toUAPIConfig()
                    var tunnelAddr = config.tunnelAddress
                    var dnsServers = config.dnsServers
                    var mtu = config.mtu

                    if (config.mode == TunnelMode.SRTP_WRAP_A) {
                        onStage(vpnService.getString(R.string.free_access_stage_auth))
                        val provJson = awaitWrapAProvision(handle, ::isAborted)
                        if (isAborted()) {
                            tearDownNative()
                            return@withLock
                        }
                        if (!provJson.isNullOrBlank()) {
                            try {
                                val element = Json.parseToJsonElement(provJson).jsonObject
                                element["uapi"]?.jsonPrimitive?.content?.let { uapiConfig = it }
                                element["address"]?.jsonPrimitive?.content?.let { tunnelAddr = it }
                                element["dns"]?.jsonPrimitive?.content?.let { dnsServers = it }
                                element["mtu"]?.jsonPrimitive?.content?.toIntOrNull()?.let { provMtu ->
                                    if (provMtu in 1200..1280) mtu = provMtu
                                }
                                logDiagnostic("WRAP-A provisioned: addr=$tunnelAddr mtu=$mtu")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing WRAP-A provision: ${e.message}")
                            }
                        } else {
                            val authError = combineAuthErrors(handle)
                            Log.e(TAG, "WRAP-A provision failed: authError=$authError")
                            logDiagnostic("WRAP-A provision failed: $authError")
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

                    onStage(vpnService.getString(R.string.free_access_stage_tunnel))
                    val builder = vpnService.Builder()
                        .setSession("Свободный доступ")
                        .setMtu(mtu.coerceIn(1200, 1280))
                        .setBlocking(false)

                    val addrParts = tunnelAddr.split("/")
                    val ip = addrParts[0].trim()
                    val prefix = addrParts.getOrNull(1)?.toIntOrNull() ?: 24
                    builder.addAddress(ip, prefix)
                    builder.addRoute("0.0.0.0", 0)

                    // Trap IPv6 to prevent cellular leaks — fail closed instead of silently leaking IPv6
                    try {
                        builder.addAddress("fd00:77:77::2", 64)
                        builder.addRoute("::", 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure IPv6 route: ${e.message}")
                        logDiagnostic("Aborted: failed to configure IPv6 route: ${e.message}")
                        tearDownNative()
                        if (!isAborted()) onError(vpnService.getString(R.string.free_access_ipv6_setup_failed))
                        return@withLock
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
                        logDiagnostic("VpnService.Builder.establish() returned null")
                        tearDownNative()
                        if (!isAborted()) onError("Не удалось создать сетевой интерфейс")
                        return@withLock
                    }

                    val pfdClaimed = synchronized(nativeLock) {
                        if (isAborted()) false else {
                            tunPfd = pfd
                            true
                        }
                    }
                    if (!pfdClaimed) {
                        runCatching { pfd.close() }
                        tearDownNative()
                        return@withLock
                    }

                    val attachRes = synchronized(nativeOpLock) {
                        val stillValid = synchronized(nativeLock) {
                            !isAborted() && tunnelHandle == handle && tunPfd == pfd
                        }
                        if (!stillValid) -99 else WgTurnCore.attachWireGuard(handle, uapiConfig, pfd.fd)
                    }
                    if (attachRes == -99) {
                        tearDownNative()
                        return@withLock
                    }
                    if (attachRes < 0) {
                        Log.e(TAG, "attachWireGuard failed with code $attachRes")
                        logDiagnostic("attachWireGuard failed: $attachRes")
                        tearDownNative()
                        if (!isAborted()) onError("Ошибка связывания сетевого туннеля ($attachRes)")
                        return@withLock
                    }

                    Log.i(TAG, "TUN and WireGuard attached, waiting for handshake...")
                    logDiagnostic("TUN attached, awaiting WireGuard handshake")
                    onStage(vpnService.getString(R.string.free_access_stage_handshake))
                    startStatsAndHandshake(handle, myGen, onReady, onTraffic, onError)
                } catch (e: CancellationException) {
                    tearDownNative()
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Error starting Free Access tunnel", t)
                    logDiagnostic("Start error: ${t.message}")
                    tearDownNative()
                    if (!isAborted()) onError(t.message ?: "Ошибка подключения")
                }
            }
        }
    }

    private suspend fun awaitBootstrapReady(
        handle: Int,
        isAborted: () -> Boolean,
        onStage: (String) -> Unit,
        onCaptcha: (String, String) -> Unit,
    ): BootstrapResult {
        var deadline = SystemClock.elapsedRealtime() + BOOTSTRAP_TIMEOUT_MS
        var lastCaptchaUrl = ""
        var lastCaptchaSid = ""

        while (SystemClock.elapsedRealtime() < deadline) {
            if (isAborted()) return BootstrapResult.Aborted
            val ready = WgTurnCore.waitBootstrapReady(handle, POLL_STEP_MS)
            if (isAborted()) return BootstrapResult.Aborted
            if (ready == 1) {
                if (lastCaptchaUrl.isNotEmpty()) onCaptcha("", "")
                return BootstrapResult.Ready
            }
            if (ready < 0) return BootstrapResult.Failed(ready)

            val stats = pollStatsSnapshot(handle)
            if (stats.captchaImageUrl.isNotBlank()) {
                if (stats.captchaImageUrl != lastCaptchaUrl || stats.captchaSid != lastCaptchaSid) {
                    lastCaptchaUrl = stats.captchaImageUrl
                    lastCaptchaSid = stats.captchaSid
                    deadline = maxOf(deadline, SystemClock.elapsedRealtime() + CAPTCHA_TIMEOUT_MS)
                    logDiagnostic("VK captcha challenge received (sid=${stats.captchaSid.ifBlank { "legacy" }})")
                    onStage(vpnService.getString(R.string.free_access_stage_captcha))
                    onCaptcha(stats.captchaImageUrl, stats.captchaSid)
                }
            } else if (lastCaptchaUrl.isNotEmpty()) {
                lastCaptchaUrl = ""
                lastCaptchaSid = ""
                onCaptcha("", "")
                onStage(vpnService.getString(R.string.free_access_stage_vk))
            }
            yield()
        }
        return BootstrapResult.Timeout
    }

    private suspend fun awaitWrapAProvision(handle: Int, isAborted: () -> Boolean): String? {
        val deadline = SystemClock.elapsedRealtime() + WRAP_A_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isAborted()) return null
            val prov = WgTurnCore.waitWrapAProvision(handle, POLL_STEP_MS)
            if (isAborted()) return null
            if (prov.isNotBlank()) return prov
            if (combineAuthErrors(handle).isNotBlank()) return null
            yield()
        }
        return null
    }

    private fun pollStatsSnapshot(handle: Int): NativeStats {
        val raw = runCatching { WgTurnCore.getStats(handle) }.getOrNull().orEmpty()
        if (raw.isBlank() || raw == "{}") return NativeStats()
        return runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            NativeStats(
                rxBytes = obj["rx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                txBytes = obj["tx_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                captchaImageUrl = obj["captcha_image_url"]?.jsonPrimitive?.content?.trim().orEmpty(),
                captchaSid = obj["captcha_sid"]?.jsonPrimitive?.content?.trim().orEmpty(),
                authError = obj["auth_error"]?.jsonPrimitive?.content?.trim().orEmpty(),
            )
        }.getOrDefault(NativeStats())
    }

    private fun combineAuthErrors(handle: Int): String {
        val direct = runCatching { WgTurnCore.getAuthError().trim() }.getOrDefault("")
        if (direct.isNotBlank()) return direct
        return pollStatsSnapshot(handle).authError
    }

    private fun mapBootstrapError(readyCode: Int, authError: String): String =
        Companion.mapBootstrapError(readyCode, authError)

    private fun startStatsAndHandshake(
        handle: Int,
        expectedGen: Long,
        onReady: () -> Unit,
        onTraffic: (VpnController.Traffic) -> Unit,
        onError: (String) -> Unit,
    ) {
        statsJob?.cancel()
        onTraffic(VpnController.Traffic.ZERO)
        statsJob = scope.launch {
            var lastDown = 0L
            var lastUp = 0L
            var handshakeConfirmed = false
            var handshakeWaitSec = 0

            while (isActive && !stopRequested && startGen == expectedGen && tunnelHandle == handle) {
                try {
                    val stats = pollStatsSnapshot(handle)
                    val downTotal = stats.rxBytes
                    val upTotal = stats.txBytes

                    if (!handshakeConfirmed) {
                        if (downTotal > 0) {
                            handshakeConfirmed = true
                            _isConnected.value = true
                            Log.i(TAG, "WireGuard handshake confirmed (rx_bytes=$downTotal)")
                            logDiagnostic("WireGuard handshake confirmed (rx=$downTotal)")
                            onReady()
                        } else {
                            handshakeWaitSec++
                            if (handshakeWaitSec >= HANDSHAKE_TIMEOUT_SEC) {
                                val authErr = combineAuthErrors(handle)
                                Log.e(TAG, "WireGuard handshake timed out after ${HANDSHAKE_TIMEOUT_SEC}s, authErr=$authErr")
                                logDiagnostic("Handshake timeout (${HANDSHAKE_TIMEOUT_SEC}s), authErr=$authErr")
                                tearDownNative()
                                if (!stopRequested && startGen == expectedGen) {
                                    val msg = if (authErr.isNotBlank()) {
                                        mapBootstrapError(-1, authErr)
                                    } else {
                                        vpnService.getString(R.string.free_access_handshake_timeout, HANDSHAKE_TIMEOUT_SEC)
                                    }
                                    onError(msg)
                                }
                                return@launch
                            }
                        }
                    }

                    val downSpeed = if (lastDown > 0) (downTotal - lastDown).coerceAtLeast(0) else 0L
                    val upSpeed = if (lastUp > 0) (upTotal - lastUp).coerceAtLeast(0) else 0L
                    lastDown = downTotal
                    lastUp = upTotal
                    onTraffic(
                        VpnController.Traffic(
                            up = upSpeed,
                            down = downSpeed,
                            totalUp = upTotal,
                            totalDown = downTotal,
                        )
                    )
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

        var handleToClose: Int
        var pfdToClose: ParcelFileDescriptor?
        synchronized(nativeLock) {
            handleToClose = tunnelHandle
            tunnelHandle = -1
            pfdToClose = tunPfd
            tunPfd = null
        }

        if (handleToClose > 0) {
            logDiagnostic("Closing tunnel handle=$handleToClose")
            synchronized(nativeOpLock) {
                runCatching { WgTurnCore.turnOff(handleToClose) }
                    .onFailure { Log.w(TAG, "Error in turnOff", it) }
            }
        }

        runCatching { pfdToClose?.close() }
    }

    fun stop() {
        synchronized(nativeLock) {
            stopRequested = true
            startGen++
        }
        startJob?.cancel()
        startJob = null
        tearDownNative()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
