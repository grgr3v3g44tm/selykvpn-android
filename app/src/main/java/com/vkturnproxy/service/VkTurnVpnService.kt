package com.vkturnproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.vkturnproxy.R
import com.vkturnproxy.core.WgTurnCore
import com.vkturnproxy.model.ProxyMode
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.model.TunnelStats
import com.vkturnproxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    WAITING_TURN,
    CONNECTED,
    ERROR
}

class VkTurnVpnService : VpnService() {

    private var tunnelHandle: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private var isCsqttMode = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Register this service for native socket protection
        WgTurnCore.registerSocketProtector(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (configJson != null) {
                    val config = Gson().fromJson(configJson, ServerConfig::class.java)
                    startTunnel(config)
                }
            }
            ACTION_STOP -> {
                stopTunnel()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: ServerConfig) {
        currentServerName = config.name
        _statusFlow.value = VpnStatus.CONNECTING
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация звонка VK…"))

        serviceScope.launch {
            try {
                // 1. Setup VK Cookie Auth if enabled
                val isCookieAuth = config.useCookieAuth || com.vkturnproxy.auth.VKCookieStore.isEnabled
                if (isCookieAuth) {
                    val cookie = com.vkturnproxy.auth.VKCookieStore.getValidCookieHeader()
                    if (!cookie.isNullOrBlank()) {
                        val links = config.vkLink.split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val linksJson = Gson().toJson(links)
                        Log.i(TAG, "Enabling VK Cookie Auth with ${links.size} call link(s)")
                        WgTurnCore.setVKCookieAuth(true, cookie, linksJson)
                        config.useCookieAuth = true
                    } else {
                        Log.w(TAG, "VK Cookie Auth enabled but no valid session cookie found; using anonymous")
                        WgTurnCore.setVKCookieAuth(false, "", "")
                        config.useCookieAuth = false
                    }
                } else {
                    WgTurnCore.setVKCookieAuth(false, "", "")
                }

                WgTurnCore.setUplinkPace(config.uplinkPaceKiB, config.uplinkPaceBurstKiB)
                WgTurnCore.setForceLegacyCaptcha(config.forceLegacyCaptcha)
                WgTurnCore.setMemstatsFastTicks(config.memstatsFastTicks)

                val proxyConfigJson = config.toProxyConfigJson()
                Log.i(TAG, "Starting proxy with mode=${config.mode}, cookieAuth=${config.useCookieAuth}, pace=${config.uplinkPaceKiB}KiB")

                // 2. Launch bootstrap in Go
                val handle = if (isCsqttMode) {
                    WgTurnCore.csqttStart(proxyConfigJson)
                } else {
                    WgTurnCore.startVKBootstrap(proxyConfigJson)
                }

                if (handle <= 0) {
                    Log.e(TAG, "Failed to start bootstrap, handle=$handle")
                    _statusFlow.value = VpnStatus.ERROR
                    stopSelf()
                    return@launch
                }
                tunnelHandle = handle

                _statusFlow.value = VpnStatus.WAITING_TURN
                updateNotification("Ожидание TURN соединения…")

                // 2. Wait for TURN allocation & DTLS readiness
                val ready = if (isCsqttMode) {
                    WgTurnCore.csqttWaitReady(handle, 120_000)
                } else {
                    WgTurnCore.waitBootstrapReady(handle, 120_000)
                }

                if (ready != 1) {
                    Log.e(TAG, "Bootstrap failed or timed out: ready=$ready")
                    _statusFlow.value = VpnStatus.ERROR
                    stopTunnel()
                    return@launch
                }

                var uapiConfig = config.toUAPIConfig()
                var tunnelAddr = config.tunnelAddress
                var dnsServers = config.dnsServers
                var mtu = config.mtu

                // 3. Special handling for WRAP-A (auto-provision from server)
                if (config.mode == ProxyMode.SRTP_WRAP_A) {
                    val provJson = WgTurnCore.waitWrapAProvision(handle, 30_000)
                    if (provJson.isNotBlank()) {
                        try {
                            val map = Gson().fromJson(provJson, Map::class.java)
                            uapiConfig = map["uapi"] as? String ?: uapiConfig
                            tunnelAddr = map["address"] as? String ?: tunnelAddr
                            dnsServers = map["dns"] as? String ?: dnsServers
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing WRAP-A provision", e)
                        }
                    }
                }

                // 4. Create Android TUN interface
                val builder = Builder()
                    .setSession("VK TURN Proxy")
                    .setMtu(mtu.coerceIn(1280, 1500))

                val addrParts = tunnelAddr.split("/")
                val ip = addrParts[0].trim()
                val prefix = addrParts.getOrNull(1)?.toIntOrNull() ?: 24
                builder.addAddress(ip, prefix)
                builder.addRoute("0.0.0.0", 0)

                for (dns in dnsServers.split(",")) {
                    val trimmedDns = dns.trim()
                    if (trimmedDns.isNotEmpty()) {
                        builder.addDnsServer(trimmedDns)
                    }
                }

                // Apply Split Tunneling (Per-App Bypass)
                val splitTunnelManager = SplitTunnelManager(applicationContext)
                splitTunnelManager.applyToBuilder(builder, packageName)

                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "VpnService.Builder.establish() returned null")
                    _statusFlow.value = VpnStatus.ERROR
                    stopTunnel()
                    return@launch
                }
                tunPfd = pfd
                val tunFd = pfd.fd
                Log.i(TAG, "TUN interface established, fd=$tunFd")

                // 5. Attach TUN to WireGuard or CSQTT engine
                val attachRes = if (isCsqttMode) {
                    WgTurnCore.csqttAttach(handle, tunFd)
                } else {
                    WgTurnCore.attachWireGuard(handle, uapiConfig, tunFd)
                }

                if (attachRes < 0) {
                    Log.e(TAG, "Attach failed with code: $attachRes")
                    _statusFlow.value = VpnStatus.ERROR
                    stopTunnel()
                    return@launch
                }

                // 6. Apply Root Tethering if enabled
                val prefs = getSharedPreferences("vkturn_settings", Context.MODE_PRIVATE)
                val isHotspotEnabled = prefs.getBoolean("root_hotspot_enabled", false)
                if (isHotspotEnabled) {
                    val firstDns = dnsServers.split(",").firstOrNull()?.trim() ?: "1.1.1.1"
                    TetheringManager.applyRules(enable = true, tunnelIface = "tun+", dnsServer = firstDns)
                }

                // 7. Auto-start LAN Proxy if enabled
                val isLocalProxyEnabled = prefs.getBoolean("local_proxy_enabled", false)
                if (isLocalProxyEnabled) {
                    HttpProxyServer.start(8080, 1080)
                }

                _statusFlow.value = VpnStatus.CONNECTED
                updateNotification("Подключено — ${config.name}")
                startStatsPolling()

            } catch (e: Exception) {
                Log.e(TAG, "Error in VPN lifecycle", e)
                _statusFlow.value = VpnStatus.ERROR
                stopTunnel()
            }
        }
    }

    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0

    private fun startStatsPolling() {
        statsJob?.cancel()
        lastRxBytes = 0
        lastTxBytes = 0
        statsJob = serviceScope.launch {
            while (isActive && tunnelHandle > 0) {
                try {
                    val statsJson = if (isCsqttMode) {
                        WgTurnCore.csqttGetStats(tunnelHandle)
                    } else {
                        WgTurnCore.getStats(tunnelHandle)
                    }
                    if (statsJson.isNotBlank() && statsJson != "{}") {
                        val parsed = Gson().fromJson(statsJson, TunnelStats::class.java)
                        val speedRx = if (lastRxBytes > 0) (parsed.bytesRx - lastRxBytes).coerceAtLeast(0) else 0
                        val speedTx = if (lastTxBytes > 0) (parsed.bytesTx - lastTxBytes).coerceAtLeast(0) else 0
                        lastRxBytes = parsed.bytesRx
                        lastTxBytes = parsed.bytesTx
                        val turnIp = if (isCsqttMode) "" else WgTurnCore.getTURNServerIP(tunnelHandle)
                        _statsFlow.value = parsed.copy(
                            speedRxBps = speedRx,
                            speedTxBps = speedTx,
                            turnServer = turnIp
                        )
                    }
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stopTunnel() {
        statsJob?.cancel()
        statsJob = null

        val prefs = getSharedPreferences("vkturn_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("root_hotspot_enabled", false)) {
            TetheringManager.applyRules(enable = false)
        }

        // Stop LAN proxy if it was running
        HttpProxyServer.stop()

        if (tunnelHandle > 0) {
            val handle = tunnelHandle
            tunnelHandle = -1
            try {
                if (isCsqttMode) {
                    WgTurnCore.csqttTurnOff(handle)
                } else {
                    WgTurnCore.turnOff(handle)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping tunnel handle $handle", e)
            }
        }

        try {
            tunPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tunPfd", e)
        }
        tunPfd = null

        _statusFlow.value = VpnStatus.DISCONNECTED
        _statsFlow.value = TunnelStats()

        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIFICATION_ID)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Статус VPN соединения",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VK TURN Proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        var instance: VkTurnVpnService? = null
            private set

        const val ACTION_START = "com.vkturnproxy.ACTION_START"
        const val ACTION_STOP = "com.vkturnproxy.ACTION_STOP"
        const val EXTRA_CONFIG_JSON = "EXTRA_CONFIG_JSON"

        private const val TAG = "VkTurnVpnService"
        private const val CHANNEL_ID = "vkturn_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        private val _statusFlow = MutableStateFlow(VpnStatus.DISCONNECTED)
        val statusFlow = _statusFlow.asStateFlow()

        private val _statsFlow = MutableStateFlow(TunnelStats())
        val statsFlow = _statsFlow.asStateFlow()

        var currentServerName: String = ""
            private set

        fun start(context: Context, config: ServerConfig) {
            val intent = Intent(context, VkTurnVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_JSON, Gson().toJson(config))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VkTurnVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
