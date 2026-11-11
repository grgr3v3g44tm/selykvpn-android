package xyz.selykvpn.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.vkturnproxy.core.WgTurnCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.model.FreeAccessConfig

/**
 * Isolated VPN service running inside android:process=":vkturn" so libwgturn.so's Go runtime
 * never shares a Linux process with libxray/libgojni.so.
 */
class FreeAccessVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val engine by lazy { FreeAccessEngine(this) }

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var currentState: String = STATE_IDLE

    @Volatile
    private var connectedSince: Long = 0L

    @Volatile
    private var latestTraffic: VpnController.Traffic = VpnController.Traffic.ZERO

    private val ipcMessenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_REQUEST_SYNC) {
                    emitStatus(error = null)
                    true
                } else {
                    false
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        runCatching { WgTurnCore.registerSocketProtector(this) }
            .onFailure { Log.e(TAG, "Failed to register socket protector", it) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_BIND_IPC) {
            ipcMessenger.binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                val handoff = intent.getBooleanExtra(EXTRA_HANDOFF, false)
                scope.launch { lock.withLock { stopInternal(handoff = handoff) } }
            }
            ACTION_START, ACTION_RECONNECT -> {
                val reconnect = intent.action == ACTION_RECONNECT
                enterForeground(getString(R.string.notification_connecting), connected = false)
                val cfgJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val cookie = intent.getStringExtra(EXTRA_COOKIE_HEADER)
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS)?.toSet().orEmpty()
                scope.launch {
                    lock.withLock {
                        if (!reconnect && running) return@withLock
                        startInternal(cfgJson, cookie, excluded)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInternal(cfgJson: String, cookie: String?, excluded: Set<String>) {
        val cfg = runCatching {
            if (cfgJson.isNotBlank()) AppJson.decodeFromString<FreeAccessConfig>(cfgJson) else FreeAccessConfig()
        }.getOrDefault(FreeAccessConfig())

        engine.stop()
        running = true
        currentState = STATE_CONNECTING
        connectedSince = 0L
        latestTraffic = VpnController.Traffic.ZERO
        emitStatus()

        engine.start(
            config = cfg,
            validCookie = cookie,
            excludedApps = excluded,
            onReady = {
                if (!running) return@start
                currentState = STATE_CONNECTED
                if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
                enterForeground(getString(R.string.free_access_connected), connected = true)
                emitStatus()
            },
            onTraffic = { t ->
                if (!running) return@start
                latestTraffic = t
                if (currentState == STATE_CONNECTED) emitStatus()
            },
            onError = { msg ->
                Log.e(TAG, "Free access error: $msg")
                running = false
                emitStatus(error = msg)
                scope.launch { lock.withLock { stopInternal(handoff = false) } }
            },
        )
    }

    private suspend fun stopInternal(handoff: Boolean) {
        if (!handoff) {
            currentState = STATE_STOPPING
            emitStatus()
        }
        running = false
        engine.stop()
        connectedSince = 0L
        latestTraffic = VpnController.Traffic.ZERO
        currentState = STATE_IDLE
        if (!handoff) {
            emitStatus()
        }
        withContext(NonCancellable + Dispatchers.Main) {
            ServiceCompat.stopForeground(this@FreeAccessVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun emitStatus(error: String? = null) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, currentState)
            .putExtra(EXTRA_SINCE, connectedSince)
            .putExtra(EXTRA_UP_SPEED, latestTraffic.up)
            .putExtra(EXTRA_DOWN_SPEED, latestTraffic.down)
            .putExtra(EXTRA_TOTAL_UP, latestTraffic.totalUp)
            .putExtra(EXTRA_TOTAL_DOWN, latestTraffic.totalDown)
        if (!error.isNullOrBlank()) {
            intent.putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent, "$packageName.INTERNAL_IPC")
    }

    private fun enterForeground(text: String, connected: Boolean) {
        val notification = Notifications.vpn(
            context = this,
            text = text,
            connected = connected,
            stopServiceClass = FreeAccessVpnService::class.java,
            stopAction = ACTION_STOP,
        )
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
        scope.launch { lock.withLock { stopInternal(handoff = false) } }
    }

    override fun onDestroy() {
        running = false
        engine.stop()
        runCatching { WgTurnCore.registerSocketProtector(null) }
        if (currentState != STATE_IDLE) {
            currentState = STATE_IDLE
            connectedSince = 0L
            latestTraffic = VpnController.Traffic.ZERO
            emitStatus()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FreeAccessVpnService"

        const val ACTION_START = "xyz.selykvpn.app.FA_START"
        const val ACTION_RECONNECT = "xyz.selykvpn.app.FA_RECONNECT"
        const val ACTION_STOP = "xyz.selykvpn.app.FA_STOP"
        const val ACTION_BIND_IPC = "xyz.selykvpn.app.FA_BIND_IPC"
        const val ACTION_STATUS = "xyz.selykvpn.app.FA_STATUS"

        const val MSG_REQUEST_SYNC = 1

        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_COOKIE_HEADER = "cookie_header"
        const val EXTRA_EXCLUDED_APPS = "excluded_apps"
        const val EXTRA_HANDOFF = "handoff"

        const val EXTRA_STATE = "state"
        const val EXTRA_SINCE = "since"
        const val EXTRA_UP_SPEED = "up_speed"
        const val EXTRA_DOWN_SPEED = "down_speed"
        const val EXTRA_TOTAL_UP = "total_up"
        const val EXTRA_TOTAL_DOWN = "total_down"
        const val EXTRA_ERROR = "error"

        const val STATE_IDLE = "idle"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_STOPPING = "stopping"

        fun startIntent(
            context: Context,
            config: FreeAccessConfig,
            validCookie: String?,
            excludedApps: Set<String>,
            reconnect: Boolean = false,
        ): Intent = Intent(context, FreeAccessVpnService::class.java).apply {
            action = if (reconnect) ACTION_RECONNECT else ACTION_START
            putExtra(EXTRA_CONFIG_JSON, AppJson.encodeToString(config))
            if (!validCookie.isNullOrBlank()) {
                putExtra(EXTRA_COOKIE_HEADER, validCookie)
            }
            putStringArrayListExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
        }

        fun stopIntent(context: Context, handoff: Boolean = false): Intent =
            Intent(context, FreeAccessVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_HANDOFF, handoff)
            }
    }
}
