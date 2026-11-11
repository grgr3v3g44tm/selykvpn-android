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

object WhitelistRules {
    private fun normalize(text: String) = text.replace("️", "")

    fun classify(blockedOk: Boolean, allowedOk: Boolean): WhitelistStatus = when {
        blockedOk -> WhitelistStatus.ALL_OK
        allowedOk -> WhitelistStatus.WHITELIST
        else -> WhitelistStatus.NO_INTERNET
    }

    /** All words appear in the server name ("LTE" and "🎲"). */
    fun matches(entry: ServerEntry, words: List<String>): Boolean =
        words.isNotEmpty() && entry.kind != ServerKind.SEPARATOR &&
            words.all { normalize(entry.remarks).contains(normalize(it), ignoreCase = true) }

    fun isBypass(entry: ServerEntry, config: WhitelistConfig): Boolean =
        config.bypassServer.any { normalize(entry.remarks).contains(normalize(it), ignoreCase = true) }

    /** Servers meant for white lists ("Все операторы", "LTE"): the only ones worth pinging while they are on. */
    fun worksUnderWhitelist(entry: ServerEntry, config: WhitelistConfig): Boolean =
        config.pingServers.any { normalize(entry.remarks).contains(normalize(it), ignoreCase = true) }

    /**
     * Which server to switch to when the status changes, or null to keep the current one.
     * White lists on: move to the LTE auto server unless a bypass or LTE server is already chosen.
     * White lists off: move back to the Wi-Fi auto server only from a bypass server, or from the
     * server the app itself chose earlier; anything the user picked by hand stays.
     */
    fun target(
        old: WhitelistStatus,
        new: WhitelistStatus,
        current: ServerEntry,
        servers: List<ServerEntry>,
        config: WhitelistConfig,
        autoSwitchedKey: String?,
    ): ServerEntry? = when {
        new == WhitelistStatus.WHITELIST && old != WhitelistStatus.WHITELIST -> {
            if (isBypass(current, config) || matches(current, config.lteServer)) null
            else servers.firstOrNull { matches(it, config.lteServer) }
        }
        new == WhitelistStatus.ALL_OK && old != WhitelistStatus.ALL_OK -> {
            val wasAutoSwitched = current.key == autoSwitchedKey
            val fromBypass = old == WhitelistStatus.WHITELIST && isBypass(current, config)
            if (wasAutoSwitched || fromBypass) servers.firstOrNull { matches(it, config.wifiServer) }?.takeIf { it.key != current.key }
            else null
        }
        else -> null
    }
}

/**
 * Checks (directly, bypassing the VPN) whether the operator has turned on white lists: when the app
 * is opened, when the network changes and every few minutes while connected; switches servers
 * accordingly. With the app closed and the VPN off it does nothing at all, to save battery.
 */
class WhitelistMonitor(
    private val context: Context,
    private val http: Http,
    private val settings: SettingsStore,
    private val remoteConfig: RemoteConfigRepository,
    private val subscriptions: SubscriptionRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(WhitelistStatus.UNKNOWN)
    val status: StateFlow<WhitelistStatus> = _status.asStateFlow()

    private val _lastCheck = MutableStateFlow<WhitelistCheck?>(null)
    val lastCheck: StateFlow<WhitelistCheck?> = _lastCheck.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Server the app switched to, for a message in the UI. */
    private val _switched = MutableSharedFlow<ServerEntry>(extraBufferCapacity = 4)
    val switched: SharedFlow<ServerEntry> = _switched.asSharedFlow()

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
            autoSwitch(old, new, config)
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

    private suspend fun autoSwitch(old: WhitelistStatus, new: WhitelistStatus, config: WhitelistConfig) {
        val info = subscriptions.state.value ?: return
        val current = subscriptions.selectedServer() ?: return
        val target = WhitelistRules.target(old, new, current, info.selectable, config, settings.whitelistAutoSwitchedNow())
        if (new == WhitelistStatus.ALL_OK && old != WhitelistStatus.ALL_OK && target != null) {
            settings.setWhitelistAutoSwitched(null)
        }
        target ?: return
        settings.setSelectedServer(target.key)
        if (new == WhitelistStatus.WHITELIST) settings.setWhitelistAutoSwitched(target.key)
        VpnController.reconnect(context)
        _switched.tryEmit(target)
    }

    private companion object {
        const val PERIOD_MS = 5 * 60_000L
    }
}
