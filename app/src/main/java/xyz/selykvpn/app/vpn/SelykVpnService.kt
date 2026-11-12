package xyz.selykvpn.app.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import xyz.selykvpn.app.BuildConfig
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.MainActivity
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.RoutingSettings
import xyz.selykvpn.app.data.ServerEntry
import xyz.selykvpn.app.data.ShareSettings
import xyz.selykvpn.app.data.UPDATE_FROM_SUBSCRIPTION
import xyz.selykvpn.app.data.UPDATE_MANUAL
import xyz.selykvpn.app.data.UnderlyingNetwork
import xyz.selykvpn.app.data.exclusionSettings
import xyz.selykvpn.app.model.FreeAccessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

class SelykVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null

    private val isTunnelActive: Boolean
        get() = tun != null

    private var statsJob: Job? = null
    private var watchJob: Job? = null

    /** What the running tunnel was built with, to notice when settings or the server's list change. */
    private data class Applied(val excluded: Set<String>, val share: ShareSettings?, val routing: RoutingSettings)

    @Volatile
    private var applied: Applied? = null

    /** Raw server config the running tunnel was built from. */
    @Volatile
    private var appliedConfig: String? = null

    /** The server auto-select found best a moment ago, to start the next core with it as the fallback. */
    private data class AutoBest(val serverKey: String, val tag: String, val at: Long)

    @Volatile
    private var autoBest: AutoBest? = null

    /** Auto-select servers that were carrying traffic at the last look. */
    private var autoInUse: Set<String> = emptySet()
    private var lastHealthRestart = 0L
    private var screenOffAt = 0L

    // After a long sleep the connections to the servers may be dead (the operator dropped them):
    // check auto-select's servers as soon as the screen turns on instead of waiting for its next round.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    val off = screenOffAt
                    if (off > 0 && SystemClock.elapsedRealtime() - off >= LONG_SLEEP_MS) scope.launch { checkAutoSelect(afterSleep = true) }
                }
            }
        }
    }
    @Volatile
    private var screenReceiverRegistered = false
    private val healthLock = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Do not blindly restart on sticky null intents after failure/kill.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                val handoff = intent.getBooleanExtra(EXTRA_HANDOFF, false)
                scope.launch { lock.withLock { stopInternal(handoff = handoff) } }
            }
            ACTION_RECONNECT -> scope.launch {
                lock.withLock { if (isTunnelActive) startInternal(reconnect = true) }
            }
            else -> {
                // ACTION_START from the app or SERVICE_INTERFACE for always-on VPN.
                enterForeground(getString(R.string.notification_connecting), connected = false)
                scope.launch { lock.withLock { if (!isTunnelActive) startInternal(reconnect = false) } }
            }
        }
        return START_NOT_STICKY
    }

    /** Must be called with [lock] held. */
    private suspend fun startInternal(reconnect: Boolean) {
        val graph = SelykApp.graph
        val previous = VpnController.state.value as? VpnController.State.Connected
        try {
            val mode = graph.settings.activeMode.first()
            if (mode == "free_access") {
                // Hand off Always-On / tile starts cleanly to the isolated :vkturn process without loading libxray.
                val freeAccessCfgStr = graph.settings.freeAccessConfig.first()
                val cfg = if (!freeAccessCfgStr.isNullOrBlank()) {
                    runCatching { AppJson.decodeFromString<FreeAccessConfig>(freeAccessCfgStr) }
                        .getOrDefault(FreeAccessConfig())
                } else {
                    FreeAccessConfig()
                }
                val excluded = exclusionSettings(graph.settings, graph.remoteConfig).first().effective
                val logEnabled = graph.settings.connectionLogNow()
                withContext(Dispatchers.Main) {
                    VpnController.startFreeAccess(
                        applicationContext,
                        cfg,
                        excluded,
                        reconnect = reconnect,
                        logEnabled = logEnabled,
                    )
                }
                stopInternal(handoff = true)
                return
            }

            if (!reconnect) VpnController.state.value = VpnController.State.Connecting
            VpnController.activeBackend = VpnController.Backend.REGULAR
            val excluded = exclusionSettings(graph.settings, graph.remoteConfig).first().effective
            val server = graph.subscriptions.selectedServer() ?: error(getString(R.string.error_no_servers))
            val raw = graph.subscriptions.configFor(server.key) ?: error(getString(R.string.error_no_servers))
            val shareSettings = graph.settings.share.first().takeIf { it.enabled }
            val routing = graph.settings.routing.first()

            // Stop the old core first: it holds the share proxy ports. Keep what it has counted.
            statsJob?.cancel()
            if (reconnect) sampleTraffic(withSpeed = false)
            Xray.stop()

            val share = shareSettings?.let { sharePorts(it) }
            val appProxy = XrayConfigBuilder.AppProxy.random()
            val fallback = autoBest?.takeIf { it.serverKey == server.key && SystemClock.elapsedRealtime() - it.at < AUTO_BEST_MAX_AGE_MS }?.tag
            val config = XrayConfigBuilder.build(raw, appProxy, if (BuildConfig.DEBUG) "warning" else "error", share, routing, fallback)
            autoInUse = emptySet()
            if (!reconnect) ConnectionLog.clear()
            ConnectionLog.setOutbounds(XrayConfigBuilder.outboundProtocols(config))
            applyConnectionLog(graph.settings.connectionLogNow())
            val newTun = buildTunnel(server, excluded, routing.lanDirect).establish()
                ?: error(getString(R.string.vpn_permission_denied))
            try {
                Xray.start(applicationContext, config, newTun.fd)
            } catch (e: Exception) {
                newTun.close()
                throw e
            }
            tun?.close()
            tun = newTun
            applied = Applied(excluded, shareSettings, routing)
            appliedConfig = raw
            if (!reconnect) VpnController.traffic.value = VpnController.Traffic.ZERO
            VpnController.appProxy.value = appProxy
            VpnController.share.value = share
            VpnController.state.value = VpnController.State.Connected(
                since = previous?.since ?: System.currentTimeMillis(),
                serverKey = server.key,
            )
            val text = getString(R.string.notification_connected, server.displayName()) +
                if (share != null) " · " + getString(R.string.share_notification) else ""
            enterForeground(text, connected = true)
            startStats()
            if (!reconnect) startWatchers()
        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed", e)
            VpnController.reportError(e.message ?: e.javaClass.simpleName)
            stopInternal()
        }
    }

    private fun buildTunnel(server: ServerEntry, excluded: Set<String>, lanDirect: Boolean): Builder {
        val builder = Builder()
            .setSession(server.displayName())
            .setMtu(XrayConfigBuilder.MTU)
            .addAddress(TUN_IPV4, 30)
            .addAddress(TUN_IPV6, 126)
            .addDnsServer(TUN_DNS)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        (TunRoutes.ipv4(lanDirect) + TunRoutes.ipv6(lanDirect)).forEach { builder.addRoute(it.address, it.prefix) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        // The app's own traffic (Xray's connections to the servers) must not loop back into the tunnel.
        builder.addDisallowedApplication(packageName)
        excluded.filter { it != packageName }.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // not installed on this device
            }
        }
        return builder
    }

    /** The ports from settings (8080 and 1080 by default), or the next free ones. */
    private fun sharePorts(settings: ShareSettings): XrayConfigBuilder.ShareProxy? {
        val http = freePort(settings.httpPort, exclude = -1) ?: return null
        val socks = freePort(settings.socksPort, exclude = http) ?: return null
        return if (settings.auth && settings.user.isNotEmpty() && settings.password.isNotEmpty()) {
            XrayConfigBuilder.ShareProxy(http, socks, settings.user, settings.password)
        } else {
            XrayConfigBuilder.ShareProxy(http, socks)
        }
    }

    private fun freePort(preferred: Int, exclude: Int): Int? =
        (preferred until minOf(preferred + 20, ShareSettings.MAX_PORT + 1)).firstOrNull { port ->
            port != exclude && try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress("0.0.0.0", port))
                }
                true
            } catch (_: IOException) {
                false
            }
        }

    /**
     * While connected: re-read the server's settings every few hours (through the tunnel) and
     * rebuild the tunnel when the list of apps without VPN or the sharing switch changes.
     */
    @OptIn(FlowPreview::class)
    private fun startWatchers() {
        watchJob?.cancel()
        val graph = SelykApp.graph
        watchJob = scope.launch {
            launch {
                delay(5_000)
                while (isActive) {
                    graph.remoteConfig.refreshIfOlderThan(CONFIG_MAX_AGE_MS)
                    delay(CONFIG_MAX_AGE_MS)
                }
            }
            launch { refreshSubscriptionPeriodically() }
            launch { graph.settings.connectionLog.distinctUntilChanged().collect { applyConnectionLog(it) } }
            launch { restartOnNetworkChange() }
            launch {
                // Coroutine timers don't run while the phone sleeps, so this costs nothing then.
                while (isActive) {
                    delay(AUTO_CHECK_MS)
                    checkAutoSelect(afterSleep = false)
                }
            }
            if (!screenReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                ContextCompat.registerReceiver(this@SelykVpnService, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                screenReceiverRegistered = true
            }
            combine(exclusionSettings(graph.settings, graph.remoteConfig), graph.settings.share, graph.settings.routing) { exclusions, share, routing ->
                Applied(exclusions.effective, share.takeIf { it.enabled }, routing)
            }
                .debounce(1_500)
                .collect { wanted ->
                    lock.withLock {
                        if (tun != null && applied != null && wanted != applied) startInternal(reconnect = true)
                    }
                }
        }
    }

    /**
     * Keeps the subscription fresh while connected (through the tunnel) at the interval from
     * settings, and rebuilds the tunnel if the selected server's config changed.
     */
    private suspend fun refreshSubscriptionPeriodically() {
        val graph = SelykApp.graph
        while (true) {
            val setting = graph.settings.subscriptionUpdateHoursNow()
            if (setting == UPDATE_MANUAL) {
                delay(30 * 60_000L)
                continue
            }
            val info = graph.subscriptions.state.value
            val hours = if (setting == UPDATE_FROM_SUBSCRIPTION) (info?.updateIntervalHours ?: 6) else setting
            val dueAt = (info?.fetchedAt ?: 0L) + hours.coerceIn(1, 48) * 3_600_000L
            val wait = dueAt - System.currentTimeMillis()
            if (wait > 0) {
                delay(minOf(wait, 30 * 60_000L))
                continue
            }
            val ok = runCatching { graph.subscriptions.refresh() }.isSuccess
            if (!ok) {
                delay(15 * 60_000L)
                continue
            }
            val server = graph.subscriptions.selectedServer() ?: continue
            val raw = graph.subscriptions.configFor(server.key)
            lock.withLock {
                if (tun != null && raw != null && raw != appliedConfig) startInternal(reconnect = true)
            }
        }
    }

    /**
     * Wi-Fi ↔ mobile or a new address: connections to the servers made on the old network are dead
     * but would hang for minutes (and auto-select's checks with them), so the core starts afresh.
     */
    @OptIn(FlowPreview::class)
    private suspend fun restartOnNetworkChange() {
        UnderlyingNetwork.changes(this)
            .debounce(NETWORK_SETTLE_MS)
            .filterNotNull()
            .map { it.key }
            .distinctUntilChanged()
            .drop(1) // the network the tunnel was started on
            .collect {
                Log.i(TAG, "network changed, restarting the core")
                lock.withLock { if (tun != null) startInternal(reconnect = true) }
            }
    }

    /**
     * Auto-select switches only new connections, and only after its next check (once a minute).
     * When a server it was using stops answering (checked twice), or none answers at all, the core
     * restarts so apps reconnect through a live server instead of hanging on the dead one.
     */
    private suspend fun checkAutoSelect(afterSleep: Boolean) {
        healthLock.withLock { checkAutoSelectLocked(afterSleep) }
    }

    private suspend fun checkAutoSelectLocked(afterSleep: Boolean) {
        val serverKey = (VpnController.state.value as? VpnController.State.Connected)?.serverKey ?: return
        if (serverKey == "free_access") return
        if (afterSleep) Xray.recheckServers()
        var checks = Xray.observatory() ?: return
        if (checks.isEmpty()) return
        var lost = lostServers(checks)
        if (!afterSleep && (lost.isNotEmpty() || checks.none { it.alive })) {
            // One failed check can be a hiccup: look again before dropping connections.
            Xray.recheckServers()
            checks = Xray.observatory() ?: return
            lost = lost.filter { tag -> checks.any { it.tag == tag && !it.alive } }
        }
        val alive = checks.filter { it.alive }.sortedBy { it.delay }
        alive.firstOrNull()?.let { autoBest = AutoBest(serverKey, it.tag, SystemClock.elapsedRealtime()) }
        autoInUse = alive.take(2).map { it.tag }.toSet()
        if (lost.isEmpty() && alive.isNotEmpty()) return
        if (!hasNetwork()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHealthRestart < HEALTH_RESTART_MIN_MS) return
        lastHealthRestart = now
        Log.i(TAG, "auto-select: servers $lost stopped answering (alive: ${alive.size}), restarting the core")
        lock.withLock { if (tun != null) startInternal(reconnect = true) }
    }

    private fun lostServers(checks: List<ServerCheck>): List<String> =
        autoInUse.filter { tag -> checks.any { it.tag == tag && !it.alive } }

    /** The phone's own network; without one a restart can't help. */
    private fun hasNetwork(): Boolean = UnderlyingNetwork.current(this) != null

    private fun applyConnectionLog(enabled: Boolean) {
        Xray.setConnectionListener(if (enabled) ConnectionLog.listener(applicationContext) else null)
    }

    private var lastSample = 0L

    /** Adds what the core has counted since the last call to the session totals (a server switch keeps the session). */
    @Synchronized
    private fun sampleTraffic(withSpeed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - lastSample) / 1000.0).coerceAtLeast(0.001)
        lastSample = now
        val up = XrayConfigBuilder.counters("uplink").sumOf { Xray.counter(it, reset = true) }
        val down = XrayConfigBuilder.counters("downlink").sumOf { Xray.counter(it, reset = true) }
        val old = VpnController.traffic.value
        VpnController.traffic.value = VpnController.Traffic(
            up = if (withSpeed) (up / seconds).toLong() else 0,
            down = if (withSpeed) (down / seconds).toLong() else 0,
            totalUp = old.totalUp + up,
            totalDown = old.totalDown + down,
        )
    }

    private fun startStats() {
        statsJob?.cancel()
        lastSample = SystemClock.elapsedRealtime()
        statsJob = scope.launch {
            val watchers = VpnController.traffic.subscriptionCount
            while (isActive) {
                if (watchers.value == 0) {
                    // Nobody looks at the numbers (the app is closed): don't wake up for them.
                    watchers.first { it > 0 }
                    sampleTraffic(withSpeed = false)
                }
                delay(1_000)
                sampleTraffic(withSpeed = true)
            }
        }
    }

    /** Must be called with [lock] held. */
    private suspend fun stopInternal(handoff: Boolean = false) {
        if (!handoff) {
            VpnController.state.value = VpnController.State.Stopping
        }
        statsJob?.cancel()
        statsJob = null
        if (tun != null) {
            Xray.stop()
            Xray.setConnectionListener(null)
        }
        unregisterScreenReceiver()
        VpnController.appProxy.value = null
        VpnController.share.value = null
        runCatching { tun?.close() }
        tun = null
        applied = null
        appliedConfig = null
        if (!handoff && VpnController.activeBackend != VpnController.Backend.FREE_ACCESS) {
            VpnController.activeBackend = VpnController.Backend.NONE
            VpnController.traffic.value = VpnController.Traffic.ZERO
            VpnController.state.value = VpnController.State.Idle
        }
        // May run inside the watcher that is cancelled below, so finish the service regardless.
        withContext(NonCancellable + Dispatchers.Main) {
            ServiceCompat.stopForeground(this@SelykVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        watchJob?.cancel()
        watchJob = null
    }

    private fun enterForeground(text: String, connected: Boolean) {
        val notification = Notifications.vpn(this, text, connected)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_VPN,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_VPN, notification) }
        }
    }

    override fun onRevoke() {
        // Another VPN app took over or the user revoked the permission.
        scope.launch { lock.withLock { stopInternal() } }
    }

    private fun unregisterScreenReceiver() {
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        if (tun != null) {
            Xray.stop()
        }
        runCatching { tun?.close() }
        tun = null
        VpnController.appProxy.value = null
        VpnController.share.value = null
        if (VpnController.activeBackend == VpnController.Backend.REGULAR) {
            VpnController.activeBackend = VpnController.Backend.NONE
            VpnController.state.value = VpnController.State.Idle
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun ServerEntry.displayName(): String = listOfNotNull(flag, title).joinToString(" ")

    companion object {
        private const val TAG = "SelykVpnService"
        const val ACTION_START = "xyz.selykvpn.app.START"
        const val ACTION_STOP = "xyz.selykvpn.app.STOP"
        const val ACTION_RECONNECT = "xyz.selykvpn.app.RECONNECT"
        const val EXTRA_HANDOFF = "handoff"

        private const val TUN_IPV4 = "172.19.0.1"
        private const val TUN_DNS = "172.19.0.2"
        private const val TUN_IPV6 = "fdfe:dcba:9876::1"

        private const val CONFIG_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val NETWORK_SETTLE_MS = 3_000L
        private const val AUTO_CHECK_MS = 10_000L
        private const val HEALTH_RESTART_MIN_MS = 2 * 60_000L
        private const val LONG_SLEEP_MS = 5 * 60_000L
        private const val AUTO_BEST_MAX_AGE_MS = 10 * 60_000L
    }
}
