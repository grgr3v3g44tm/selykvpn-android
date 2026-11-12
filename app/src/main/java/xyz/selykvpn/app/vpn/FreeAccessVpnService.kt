package xyz.selykvpn.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
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
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Isolated VPN service running inside android:process=":vkturn" so libwgturn.so's Go runtime
 * never shares a Linux process with libxray/libgojni.so.
 */
class FreeAccessVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val engine by lazy { FreeAccessEngine(this) }
    private val clientMessengers = CopyOnWriteArraySet<Messenger>()

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var sessionGen: Long = 0L

    @Volatile
    private var currentState: String = STATE_IDLE

    @Volatile
    private var currentStage: String? = null

    @Volatile
    private var captchaImageUrl: String = ""

    @Volatile
    private var captchaSid: String = ""

    @Volatile
    private var connectedSince: Long = 0L

    @Volatile
    private var latestTraffic: VpnController.Traffic = VpnController.Traffic.ZERO

    private val ipcMessenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    MSG_REGISTER_CLIENT -> {
                        msg.replyTo?.let { client ->
                            clientMessengers.add(client)
                            sendBundleToClient(client, buildStatusBundle())
                        }
                        true
                    }
                    MSG_UNREGISTER_CLIENT -> {
                        msg.replyTo?.let { clientMessengers.remove(it) }
                        true
                    }
                    MSG_REQUEST_SYNC -> {
                        msg.replyTo?.let { sendBundleToClient(it, buildStatusBundle()) }
                        emitStatus()
                        true
                    }
                    MSG_STOP -> {
                        val handoff = msg.arg1 == 1
                        requestStop(handoff)
                        true
                    }
                    MSG_SOLVE_CAPTCHA -> {
                        val answer = msg.data?.getString(EXTRA_CAPTCHA_ANSWER).orEmpty()
                        if (answer.isNotBlank()) {
                            scope.launch {
                                if (engine.solveCaptcha(answer)) {
                                    captchaImageUrl = ""
                                    captchaSid = ""
                                    currentStage = getString(R.string.free_access_stage_vk)
                                    emitStatus()
                                }
                            }
                        }
                        true
                    }
                    MSG_REFRESH_CAPTCHA -> {
                        scope.launch {
                            val newUrl = engine.refreshCaptchaUrl()
                            if (!newUrl.isNullOrBlank()) {
                                captchaImageUrl = newUrl
                                emitStatus()
                            }
                        }
                        true
                    }
                    MSG_SET_LOG_ENABLED -> {
                        engine.setLogEnabled(msg.arg1 == 1)
                        true
                    }
                    else -> false
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
            running = false
            currentState = STATE_IDLE
            currentStage = null
            captchaImageUrl = ""
            captchaSid = ""
            emitStatus()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                val handoff = intent.getBooleanExtra(EXTRA_HANDOFF, false)
                requestStop(handoff)
            }
            ACTION_SOLVE_CAPTCHA -> {
                val answer = intent.getStringExtra(EXTRA_CAPTCHA_ANSWER).orEmpty()
                if (answer.isNotBlank()) {
                    scope.launch { engine.solveCaptcha(answer) }
                }
            }
            ACTION_REFRESH_CAPTCHA -> {
                scope.launch {
                    val newUrl = engine.refreshCaptchaUrl()
                    if (!newUrl.isNullOrBlank()) {
                        captchaImageUrl = newUrl
                        emitStatus()
                    }
                }
            }
            ACTION_START, ACTION_RECONNECT -> {
                val reconnect = intent.action == ACTION_RECONNECT
                if (!enterForeground(getString(R.string.notification_connecting), connected = false)) {
                    running = false
                    currentState = STATE_IDLE
                    currentStage = null
                    captchaImageUrl = ""
                    captchaSid = ""
                    emitStatus(error = getString(R.string.vpn_start_failed_fgs))
                    stopSelf()
                    return START_NOT_STICKY
                }
                val cfgJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val cookie = intent.getStringExtra(EXTRA_COOKIE_HEADER)
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS)?.toSet().orEmpty()
                val logEnabled = intent.getBooleanExtra(EXTRA_LOG_ENABLED, false)
                val mySession = ++sessionGen
                if (reconnect || !running) {
                    currentState = STATE_CONNECTING
                    if (currentStage == null) {
                        currentStage = getString(R.string.free_access_stage_vk)
                    }
                }
                scope.launch {
                    lock.withLock {
                        if (sessionGen != mySession) return@withLock
                        if (!reconnect && running) {
                            emitStatus()
                            return@withLock
                        }
                        startInternal(cfgJson, cookie, excluded, logEnabled, mySession)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun requestStop(handoff: Boolean) {
        running = false
        sessionGen++
        scope.launch { lock.withLock { stopInternal(handoff = handoff) } }
    }

    private fun startInternal(cfgJson: String, cookie: String?, excluded: Set<String>, logEnabled: Boolean, mySession: Long) {
        val cfg = runCatching {
            if (cfgJson.isNotBlank()) AppJson.decodeFromString<FreeAccessConfig>(cfgJson) else FreeAccessConfig()
        }.getOrDefault(FreeAccessConfig())

        engine.stop()
        running = true
        currentState = STATE_CONNECTING
        currentStage = getString(R.string.free_access_stage_vk)
        captchaImageUrl = ""
        captchaSid = ""
        connectedSince = 0L
        latestTraffic = VpnController.Traffic.ZERO
        emitStatus()

        engine.start(
            config = cfg,
            validCookie = cookie,
            excludedApps = excluded,
            logEnabled = logEnabled,
            onStage = { stage ->
                if (!running || sessionGen != mySession) return@start
                currentStage = stage
                emitStatus()
            },
            onCaptcha = { url, sid ->
                if (!running || sessionGen != mySession) return@start
                captchaImageUrl = url
                captchaSid = sid
                emitStatus()
            },
            onReady = {
                if (!running || sessionGen != mySession) return@start
                currentState = STATE_CONNECTED
                currentStage = null
                captchaImageUrl = ""
                captchaSid = ""
                if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
                enterForeground(getString(R.string.free_access_connected), connected = true)
                emitStatus()
            },
            onTraffic = { t ->
                if (!running || sessionGen != mySession) return@start
                latestTraffic = t
                if (currentState == STATE_CONNECTED) emitStatus()
            },
            onError = { msg ->
                if (!running || sessionGen != mySession) return@start
                Log.e(TAG, "Free access error: $msg")
                running = false
                sessionGen++
                scope.launch { lock.withLock { stopInternal(handoff = false, terminalError = msg) } }
            },
        )
    }

    private suspend fun stopInternal(handoff: Boolean, terminalError: String? = null) {
        if (!handoff && terminalError.isNullOrBlank()) {
            currentState = STATE_STOPPING
            currentStage = null
            captchaImageUrl = ""
            captchaSid = ""
            emitStatus()
        }
        running = false
        engine.stop()
        connectedSince = 0L
        latestTraffic = VpnController.Traffic.ZERO
        currentState = STATE_IDLE
        currentStage = null
        captchaImageUrl = ""
        captchaSid = ""
        if (!handoff) {
            emitStatus(error = terminalError)
        }
        withContext(NonCancellable + Dispatchers.Main) {
            ServiceCompat.stopForeground(this@FreeAccessVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildStatusBundle(error: String? = null): Bundle = Bundle().apply {
        putString(EXTRA_STATE, currentState)
        putLong(EXTRA_SINCE, connectedSince)
        putLong(EXTRA_UP_SPEED, latestTraffic.up)
        putLong(EXTRA_DOWN_SPEED, latestTraffic.down)
        putLong(EXTRA_TOTAL_UP, latestTraffic.totalUp)
        putLong(EXTRA_TOTAL_DOWN, latestTraffic.totalDown)
        currentStage?.let { putString(EXTRA_STAGE, it) }
        if (captchaImageUrl.isNotBlank()) {
            putString(EXTRA_CAPTCHA_URL, captchaImageUrl)
            putString(EXTRA_CAPTCHA_SID, captchaSid)
        }
        if (!error.isNullOrBlank()) {
            putString(EXTRA_ERROR, error)
        }
    }

    private fun sendBundleToClient(client: Messenger, bundle: Bundle) {
        try {
            client.send(Message.obtain(null, MSG_STATUS_UPDATE).apply { data = bundle })
        } catch (_: DeadObjectException) {
            clientMessengers.remove(client)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send status over Messenger", e)
        }
    }

    private fun emitStatus(error: String? = null) {
        val bundle = buildStatusBundle(error)
        for (client in clientMessengers) {
            sendBundleToClient(client, bundle)
        }
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtras(bundle)
        sendBroadcast(intent, "$packageName.INTERNAL_IPC")
    }

    private fun enterForeground(text: String, connected: Boolean): Boolean {
        val notification = Notifications.vpn(
            context = this,
            text = text,
            connected = connected,
            stopServiceClass = FreeAccessVpnService::class.java,
            stopAction = ACTION_STOP,
        )
        return try {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            FreeAccessLog.appendLine(this, TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    override fun onRevoke() {
        requestStop(handoff = false)
    }

    override fun onDestroy() {
        running = false
        sessionGen++
        engine.stop()
        runCatching { WgTurnCore.registerSocketProtector(null) }
        if (currentState != STATE_IDLE) {
            currentState = STATE_IDLE
            currentStage = null
            captchaImageUrl = ""
            captchaSid = ""
            connectedSince = 0L
            latestTraffic = VpnController.Traffic.ZERO
            emitStatus()
        }
        clientMessengers.clear()
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
        const val ACTION_SOLVE_CAPTCHA = "xyz.selykvpn.app.FA_SOLVE_CAPTCHA"
        const val ACTION_REFRESH_CAPTCHA = "xyz.selykvpn.app.FA_REFRESH_CAPTCHA"

        const val MSG_REQUEST_SYNC = 1
        const val MSG_REGISTER_CLIENT = 2
        const val MSG_UNREGISTER_CLIENT = 3
        const val MSG_STATUS_UPDATE = 4
        const val MSG_STOP = 5
        const val MSG_SOLVE_CAPTCHA = 6
        const val MSG_REFRESH_CAPTCHA = 7
        const val MSG_SET_LOG_ENABLED = 8

        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_COOKIE_HEADER = "cookie_header"
        const val EXTRA_EXCLUDED_APPS = "excluded_apps"
        const val EXTRA_LOG_ENABLED = "log_enabled"
        const val EXTRA_HANDOFF = "handoff"

        const val EXTRA_STATE = "state"
        const val EXTRA_SINCE = "since"
        const val EXTRA_UP_SPEED = "up_speed"
        const val EXTRA_DOWN_SPEED = "down_speed"
        const val EXTRA_TOTAL_UP = "total_up"
        const val EXTRA_TOTAL_DOWN = "total_down"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_CAPTCHA_URL = "captcha_url"
        const val EXTRA_CAPTCHA_SID = "captcha_sid"
        const val EXTRA_CAPTCHA_ANSWER = "captcha_answer"

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
            logEnabled: Boolean = false,
        ): Intent = Intent(context, FreeAccessVpnService::class.java).apply {
            action = if (reconnect) ACTION_RECONNECT else ACTION_START
            putExtra(EXTRA_CONFIG_JSON, AppJson.encodeToString(config))
            if (!validCookie.isNullOrBlank()) {
                putExtra(EXTRA_COOKIE_HEADER, validCookie)
            }
            putStringArrayListExtra(EXTRA_EXCLUDED_APPS, ArrayList(excludedApps))
            putExtra(EXTRA_LOG_ENABLED, logEnabled)
        }

        fun stopIntent(context: Context, handoff: Boolean = false): Intent =
            Intent(context, FreeAccessVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_HANDOFF, handoff)
            }
    }
}
