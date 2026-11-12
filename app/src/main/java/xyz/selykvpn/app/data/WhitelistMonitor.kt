package xyz.selykvpn.app.data

import android.content.Context
import android.os.SystemClock
import xyz.selykvpn.app.AppVisibility
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

enum class WhitelistStatus { UNKNOWN, ALL_OK, WHITELIST, NO_INTERNET }

enum class NetworkKind { WIFI, MOBILE, ETHERNET, OTHER, NONE }

enum class ProbeError { TIMEOUT, DNS, CONNECT, TLS, OTHER }

/** How one site answered: [ms] until the response, or why there was none. */
data class ProbeResult(val url: String, val ms: Long?, val error: ProbeError?) {
    val ok: Boolean get() = error == null
    val host: String get() = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
}

/** The latest check: when, on which network and how each site answered. */
data class WhitelistCheck(
    val time: Long,
    val status: WhitelistStatus,
    val network: NetworkKind,
    /** Sites that white lists close (Google). */
    val blocked: List<ProbeResult>,
    /** Sites that stay open under white lists (VK, Yandex). */
    val allowed: List<ProbeResult>,
)

enum class ModeSwitchDecision { NONE, TO_FREE_ACCESS, TO_REGULAR, SETUP_REQUIRED, TARIFF_LOCKED }

sealed interface WhitelistEvent {
    data class Switched(val mode: String) : WhitelistEvent
    data object SetupRequired : WhitelistEvent
    data object TariffLocked : WhitelistEvent
}

object WhitelistRules {
    const val AUTO_MODE_FREE_ACCESS = "mode:free_access"

    fun classify(blockedOk: Boolean, allowedOk: Boolean): WhitelistStatus = when {
        blockedOk -> WhitelistStatus.ALL_OK
        allowedOk -> WhitelistStatus.WHITELIST
        else -> WhitelistStatus.NO_INTERNET
    }

    /**
     * Decides how the active app mode should change when operator white lists turn on or off:
     * - White lists on: switch from `regular` to `free_access` if allowed by tariff and ready;
     *   emit setup/tariff notice if not ready or unavailable.
     * - White lists off: return from `free_access` back to `regular` only when the app itself
     *   performed the switch earlier; a manual `free_access` choice stays untouched.
     */
    fun targetMode(
        old: WhitelistStatus,
        new: WhitelistStatus,
        currentMode: String,
        freeAccessAllowed: Boolean,
        freeAccessReady: Boolean,
        autoSwitchedMarker: String?,
    ): ModeSwitchDecision = when {
        new == WhitelistStatus.WHITELIST && old != WhitelistStatus.WHITELIST -> when {
            currentMode == "free_access" -> ModeSwitchDecision.NONE
            !freeAccessAllowed -> ModeSwitchDecision.TARIFF_LOCKED
            freeAccessReady -> ModeSwitchDecision.TO_FREE_ACCESS
            else -> ModeSwitchDecision.SETUP_REQUIRED
        }
        new == WhitelistStatus.ALL_OK && old != WhitelistStatus.ALL_OK -> {
            if (currentMode == "free_access" && autoSwitchedMarker == AUTO_MODE_FREE_ACCESS) {
                ModeSwitchDecision.TO_REGULAR
            } else {
                ModeSwitchDecision.NONE
            }
        }
        else -> ModeSwitchDecision.NONE
    }
}

/**
 * Checks (directly, bypassing the VPN) whether the operator has turned on white lists: when the app
 * is opened, when the network changes and every few minutes while connected; switches between
 * Standard (`regular`) and Free Access (`free_access`) modes accordingly.
 */
class WhitelistMonitor(
    private val context: Context,
    private val http: Http,
    private val settings: SettingsStore,
    private val remoteConfig: RemoteConfigRepository,
    private val subscriptions: SubscriptionRepository,
    private val accounts: AccountRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(WhitelistStatus.UNKNOWN)
    val status: StateFlow<WhitelistStatus> = _status.asStateFlow()

    private val _lastCheck = MutableStateFlow<WhitelistCheck?>(null)
    val lastCheck: StateFlow<WhitelistCheck?> = _lastCheck.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Mode-switch or setup notifications emitted for UI snackbars. */
    private val _events = MutableSharedFlow<WhitelistEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<WhitelistEvent> = _events.asSharedFlow()

    @Volatile
    var checkedAt: Long = 0L
        private set

    /** Network ("netId:kind") of the last finished check and of the running one. */
    @Volatile
    private var checkedNetwork: String? = null

    @Volatile
    private var checkingNetwork: String? = null

    private val lock = Mutex()
    private var pending: Job? = null
    private val probeClient by lazy {
        http.direct.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // A fresh connection every time: a kept-alive one could still work after the operator
            // has closed new connections to the site.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    fun start() {
        val connected = VpnController.state.map { it is VpnController.State.Connected }.distinctUntilChanged()
        // Network changes are watched only while someone can use the result: the app is open or
        // the VPN is on.
        scope.launch {
            combine(settings.whitelistDetect, AppVisibility.visible, connected) { detect, visible, vpn -> detect && (visible || vpn) }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        defaultNetwork().collect { network ->
                            if (network != checkedNetwork && network != checkingNetwork) schedule(delayMs = 3_000)
                        }
                    } else {
                        pending?.cancel()
                    }
                }
        }
        // Re-check every few minutes while connected: operators switch white lists on without a network change.
        scope.launch {
            combine(settings.whitelistDetect, connected) { detect, vpn -> detect && vpn }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        schedule(delayMs = 1_000)
                        while (true) {
                            delay(PERIOD_MS)
                            schedule(delayMs = 0)
                        }
                    }
                }
        }
    }

    /** Called when the app comes to the foreground. */
    fun checkIfStale() {
        if (SystemClock.elapsedRealtime() - checkedAt > 60_000 && pending?.isActive != true) schedule(delayMs = 0)
    }

    fun checkNow() = schedule(delayMs = 0)

    private fun schedule(delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            if (!settings.whitelistDetectNow()) return@launch
            check()
        }
    }

    /** The phone's real network (not our VPN), as "netId addresses". */
    private fun defaultNetwork(): Flow<String?> = UnderlyingNetwork.changes(context).map { it?.key }

    private suspend fun check() = lock.withLock {
        _checking.value = true
        val network = UnderlyingNetwork.current(context)
        val key = network?.key
        checkingNetwork = key
        try {
            val config = remoteConfig.state.value.whitelist
            val (blocked, allowed) = coroutineScope {
                val b = config.blockedProbes.map { url -> async { probe(url) } }
                val a = config.allowedProbes.map { url -> async { probe(url) } }
                b.awaitAll() to a.awaitAll()
            }
            val new = WhitelistRules.classify(blocked.any { it.ok }, allowed.any { it.ok })
            checkedAt = SystemClock.elapsedRealtime()
            checkedNetwork = key
            _lastCheck.value = WhitelistCheck(
                time = System.currentTimeMillis(),
                status = new,
                network = network?.kind ?: NetworkKind.NONE,
                blocked = blocked,
                allowed = allowed,
            )
            val old = _status.value
            _status.value = new
            autoSwitch(old, new)
        } finally {
            checkingNetwork = null
            _checking.value = false
        }
    }

    /** A plain HEAD request: any HTTP answer means the site is reachable. */
    private suspend fun probe(url: String): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            probeClient.newCall(Request.Builder().url(url).head().build()).await().use {
                ProbeResult(url, SystemClock.elapsedRealtime() - start, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult(url, null, errorOf(e))
        }
    }

    private fun errorOf(e: Exception): ProbeError = when (e) {
        is UnknownHostException -> ProbeError.DNS
        is SocketTimeoutException, is InterruptedIOException -> ProbeError.TIMEOUT
        is SSLException -> ProbeError.TLS
        is SocketException -> ProbeError.CONNECT
        else -> ProbeError.OTHER
    }

    private suspend fun autoSwitch(old: WhitelistStatus, new: WhitelistStatus) {
        val currentMode = settings.activeMode.first()
        val cfg = settings.freeAccessConfigNow()
        val hasSub = subscriptions.state.value != null
        val allowed = TariffAccess.isFreeAccessAllowed(hasSub, accounts.state.value, cfg)
        val decision = WhitelistRules.targetMode(
            old = old,
            new = new,
            currentMode = currentMode,
            freeAccessAllowed = allowed,
            freeAccessReady = cfg.isReadyForTunnel(),
            autoSwitchedMarker = settings.whitelistAutoSwitchedNow(),
        )
        when (decision) {
            ModeSwitchDecision.NONE -> return
            ModeSwitchDecision.SETUP_REQUIRED -> {
                _events.tryEmit(WhitelistEvent.SetupRequired)
                return
            }
            ModeSwitchDecision.TARIFF_LOCKED -> {
                _events.tryEmit(WhitelistEvent.TariffLocked)
                return
            }
            ModeSwitchDecision.TO_FREE_ACCESS -> {
                settings.setWhitelistAutoSwitched(WhitelistRules.AUTO_MODE_FREE_ACCESS)
                settings.setActiveMode("free_access")
                if (VpnController.isActive) VpnController.switchMode(context, "free_access")
                _events.tryEmit(WhitelistEvent.Switched("free_access"))
            }
            ModeSwitchDecision.TO_REGULAR -> {
                settings.setWhitelistAutoSwitched(null)
                if (!hasSub) return
                settings.setActiveMode("regular")
                if (VpnController.isActive) VpnController.switchMode(context, "regular")
                _events.tryEmit(WhitelistEvent.Switched("regular"))
            }
        }
    }

    private companion object {
        const val PERIOD_MS = 5 * 60_000L
    }
}
