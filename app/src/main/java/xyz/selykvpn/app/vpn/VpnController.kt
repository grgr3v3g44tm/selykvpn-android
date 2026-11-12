package xyz.selykvpn.app.vpn

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import xyz.selykvpn.app.R
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.exclusionSettings
import xyz.selykvpn.app.model.FreeAccessConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide VPN state shared by the service, the UI and the Quick Settings tile. */
object VpnController {

    private const val TAG = "VpnController"
    private const val START_WATCHDOG_MS = 6_000L
    private const val STOP_WATCHDOG_MS = 3_500L
    private const val SYNC_WATCHDOG_MS = 1_500L

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val since: Long, val serverKey: String) : State
        data object Stopping : State
    }

    enum class Backend { NONE, REGULAR, FREE_ACCESS }

    /** Speeds are bytes per second, totals are bytes since connecting. */
    data class Traffic(val up: Long, val down: Long, val totalUp: Long, val totalDown: Long) {
        companion object {
            val ZERO = Traffic(0, 0, 0, 0)
        }
    }

    data class CaptchaChallenge(val imageUrl: String, val sid: String = "")

    internal data class FreeAccessStatusSnapshot(
        val faState: String,
        val since: Long = 0L,
        val upSpeed: Long = 0L,
        val downSpeed: Long = 0L,
        val totalUp: Long = 0L,
        val totalDown: Long = 0L,
        val stage: String? = null,
        val captchaUrl: String? = null,
        val captchaSid: String = "",
        val error: String? = null,
    )

    val state = MutableStateFlow<State>(State.Idle)
    val traffic = MutableStateFlow(Traffic.ZERO)
    val freeAccessStage = MutableStateFlow<String?>(null)
    val captcha = MutableStateFlow<CaptchaChallenge?>(null)
    val appProxy = MutableStateFlow<XrayConfigBuilder.AppProxy?>(null)

    /** Ports of the running share proxy, or null when sharing is off or VPN is disconnected. */
    val share = MutableStateFlow<XrayConfigBuilder.ShareProxy?>(null)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Volatile
    var activeBackend: Backend = Backend.NONE
        internal set

    private val initialized = AtomicBoolean(false)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ipcLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteMessenger: Messenger? = null

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Volatile
    private var isBound = false

    @Volatile
    private var bindRequested = false

    @Volatile
    private var lastFreeAccessStartReqMs = 0L

    @Volatile
    private var lastFreeAccessEventMs = 0L

    private var startWatchdogJob: Job? = null
    private var stopWatchdogJob: Job? = null
    private var syncWatchdogJob: Job? = null

    private val clientMessenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == FreeAccessVpnService.MSG_STATUS_UPDATE) {
                    applyFreeAccessBundle(msg.data)
                    true
                } else {
                    false
                }
            }
        )
    }

    private val freeAccessConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            syncWatchdogJob?.cancel()
            val death = IBinder.DeathRecipient {
                handleFreeAccessBinderLost()
            }
            synchronized(ipcLock) {
                unlinkDeathLocked()
                remoteBinder = service
                deathRecipient = death
                remoteMessenger = service?.let { Messenger(it) }
                isBound = service != null
                if (service != null) bindRequested = true
            }
            if (service != null) {
                runCatching { service.linkToDeath(death, 0) }
                    .onFailure { Log.w(TAG, "linkToDeath failed", it) }
                sendToRemote(FreeAccessVpnService.MSG_REGISTER_CLIENT, reply = true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleFreeAccessBinderLost()
        }

        override fun onBindingDied(name: ComponentName?) {
            handleFreeAccessBinderLost()
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(ipcLock) {
                unlinkDeathLocked()
                remoteMessenger = null
                remoteBinder = null
                isBound = false
            }
            if (activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle) {
                recoverFromFreeAccessDeath(
                    unexpected = state.value !is State.Stopping,
                    errorMessage = appContext?.getString(R.string.free_access_process_died),
                )
            }
        }
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        appContext = appCtx
        val filter = IntentFilter(FreeAccessVpnService.ACTION_STATUS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                applyFreeAccessBundle(intent.extras)
            }
        }
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            filter,
            "${appCtx.packageName}.INTERNAL_IPC",
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        syncState(appCtx)
    }

    /** Re-synchronizes local controller state with `:vkturn` (e.g. on process start or `onResume`). */
    fun syncState(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (activeBackend == Backend.REGULAR) return

        val messenger = synchronized(ipcLock) { remoteMessenger }
        val binder = synchronized(ipcLock) { remoteBinder }
        if (messenger != null && binder != null && binder.isBinderAlive) {
            val sent = runCatching {
                messenger.send(
                    Message.obtain(null, FreeAccessVpnService.MSG_REQUEST_SYNC).apply {
                        replyTo = clientMessenger
                    }
                )
            }.isSuccess
            if (sent) return
            handleFreeAccessBinderLost()
            return
        }

        bindFreeAccessIfAlive(appCtx)
        if (activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle) {
            syncWatchdogJob?.cancel()
            syncWatchdogJob = controllerScope.launch {
                delay(SYNC_WATCHDOG_MS)
                val aliveNow = synchronized(ipcLock) { remoteBinder?.isBinderAlive == true }
                if (!aliveNow && activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle) {
                    val elapsedSinceStart = SystemClock.elapsedRealtime() - lastFreeAccessStartReqMs
                    if (elapsedSinceStart > SYNC_WATCHDOG_MS) {
                        recoverFromFreeAccessDeath(
                            unexpected = state.value !is State.Stopping,
                            errorMessage = appCtx.getString(R.string.free_access_process_died),
                        )
                    }
                }
            }
        }
    }

    private fun bindFreeAccessIfAlive(context: Context) {
        val intent = Intent(context, FreeAccessVpnService::class.java)
            .setAction(FreeAccessVpnService.ACTION_BIND_IPC)
        val boundNow = runCatching {
            // Flag 0 binds only if FreeAccessVpnService is already started; never spawns :vkturn.
            context.bindService(intent, freeAccessConn, 0)
        }.getOrDefault(false)
        if (boundNow) bindRequested = true
        if (!boundNow && activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - lastFreeAccessStartReqMs
            if (elapsedSinceStart > SYNC_WATCHDOG_MS) {
                recoverFromFreeAccessDeath(
                    unexpected = state.value !is State.Stopping,
                    errorMessage = context.getString(R.string.free_access_process_died),
                )
            }
        }
    }

    private fun unbindFreeAccess(context: Context) {
        sendToRemote(FreeAccessVpnService.MSG_UNREGISTER_CLIENT, reply = true)
        synchronized(ipcLock) {
            unlinkDeathLocked()
            if (isBound || bindRequested) {
                runCatching { context.unbindService(freeAccessConn) }
                isBound = false
                bindRequested = false
            }
            remoteMessenger = null
            remoteBinder = null
        }
    }

    private fun unlinkDeathLocked() {
        val binder = remoteBinder
        val death = deathRecipient
        if (binder != null && death != null) {
            runCatching { binder.unlinkToDeath(death, 0) }
        }
        deathRecipient = null
    }

    private fun sendToRemote(what: Int, arg1: Int = 0, data: Bundle? = null, reply: Boolean = false): Boolean {
        val messenger = synchronized(ipcLock) { remoteMessenger } ?: return false
        return runCatching {
            val msg = Message.obtain(null, what, arg1, 0).apply {
                if (data != null) this.data = data
                if (reply) replyTo = clientMessenger
            }
            messenger.send(msg)
            true
        }.getOrElse {
            handleFreeAccessBinderLost()
            false
        }
    }

    private fun handleFreeAccessBinderLost() {
        syncWatchdogJob?.cancel()
        startWatchdogJob?.cancel()
        val wasActiveFreeAccess: Boolean
        val wasStopping: Boolean
        synchronized(ipcLock) {
            unlinkDeathLocked()
            remoteMessenger = null
            remoteBinder = null
            if (isBound || bindRequested) {
                appContext?.let { ctx -> runCatching { ctx.unbindService(freeAccessConn) } }
                isBound = false
                bindRequested = false
            }
            wasActiveFreeAccess = activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle
            wasStopping = state.value is State.Stopping
        }
        if (wasActiveFreeAccess) {
            appContext?.let { ctx ->
                // Cancel any pending ActivityManager restart for the crashed :vkturn service.
                runCatching { ctx.stopService(Intent(ctx, FreeAccessVpnService::class.java)) }
            }
            recoverFromFreeAccessDeath(
                unexpected = !wasStopping,
                errorMessage = appContext?.getString(R.string.free_access_process_died),
            )
        }
    }

    internal fun recoverFromFreeAccessDeath(unexpected: Boolean, errorMessage: String? = null) {
        startWatchdogJob?.cancel()
        stopWatchdogJob?.cancel()
        syncWatchdogJob?.cancel()
        activeBackend = Backend.NONE
        traffic.value = Traffic.ZERO
        freeAccessStage.value = null
        captcha.value = null
        state.value = State.Idle
        if (unexpected && !errorMessage.isNullOrBlank()) {
            reportError(errorMessage)
        }
    }

    private fun applyFreeAccessBundle(bundle: Bundle?) {
        bundle ?: return
        val faState = bundle.getString(FreeAccessVpnService.EXTRA_STATE) ?: return
        applyFreeAccessStatus(
            FreeAccessStatusSnapshot(
                faState = faState,
                since = bundle.getLong(FreeAccessVpnService.EXTRA_SINCE, System.currentTimeMillis()),
                upSpeed = bundle.getLong(FreeAccessVpnService.EXTRA_UP_SPEED, 0L),
                downSpeed = bundle.getLong(FreeAccessVpnService.EXTRA_DOWN_SPEED, 0L),
                totalUp = bundle.getLong(FreeAccessVpnService.EXTRA_TOTAL_UP, 0L),
                totalDown = bundle.getLong(FreeAccessVpnService.EXTRA_TOTAL_DOWN, 0L),
                stage = bundle.getString(FreeAccessVpnService.EXTRA_STAGE),
                captchaUrl = bundle.getString(FreeAccessVpnService.EXTRA_CAPTCHA_URL),
                captchaSid = bundle.getString(FreeAccessVpnService.EXTRA_CAPTCHA_SID).orEmpty(),
                error = bundle.getString(FreeAccessVpnService.EXTRA_ERROR),
            )
        )
    }

    internal fun applyFreeAccessStatus(snapshot: FreeAccessStatusSnapshot) {
        val now = runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }
        lastFreeAccessEventMs = now

        if (!snapshot.error.isNullOrBlank()) {
            reportError(snapshot.error)
        }
        // Ignore stale Idle/Stopping broadcasts from :vkturn if user already switched to Regular mode.
        if (activeBackend == Backend.REGULAR &&
            (snapshot.faState == FreeAccessVpnService.STATE_IDLE || snapshot.faState == FreeAccessVpnService.STATE_STOPPING)
        ) {
            return
        }

        when (snapshot.faState) {
            FreeAccessVpnService.STATE_CONNECTING -> {
                stopWatchdogJob?.cancel()
                activeBackend = Backend.FREE_ACCESS
                state.value = State.Connecting
                freeAccessStage.value = snapshot.stage
                val url = snapshot.captchaUrl?.trim().orEmpty()
                captcha.value = if (url.isNotEmpty()) CaptchaChallenge(url, snapshot.captchaSid) else null
            }
            FreeAccessVpnService.STATE_CONNECTED -> {
                startWatchdogJob?.cancel()
                stopWatchdogJob?.cancel()
                activeBackend = Backend.FREE_ACCESS
                freeAccessStage.value = null
                captcha.value = null
                state.value = State.Connected(since = snapshot.since, serverKey = "free_access")
                traffic.value = Traffic(
                    up = snapshot.upSpeed,
                    down = snapshot.downSpeed,
                    totalUp = snapshot.totalUp,
                    totalDown = snapshot.totalDown,
                )
            }
            FreeAccessVpnService.STATE_STOPPING -> {
                startWatchdogJob?.cancel()
                activeBackend = Backend.FREE_ACCESS
                freeAccessStage.value = null
                captcha.value = null
                state.value = State.Stopping
            }
            FreeAccessVpnService.STATE_IDLE -> {
                startWatchdogJob?.cancel()
                stopWatchdogJob?.cancel()
                if (activeBackend == Backend.FREE_ACCESS || activeBackend == Backend.NONE) {
                    activeBackend = Backend.NONE
                    traffic.value = Traffic.ZERO
                    freeAccessStage.value = null
                    captcha.value = null
                    state.value = State.Idle
                }
            }
        }
    }

    fun reportError(message: String) {
        _errors.tryEmit(message)
    }

    val isActive: Boolean get() = state.value is State.Connected || state.value is State.Connecting

    fun startRegular(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        startWatchdogJob?.cancel()
        stopWatchdogJob?.cancel()
        syncWatchdogJob?.cancel()
        if (activeBackend == Backend.FREE_ACCESS) {
            if (!sendToRemote(FreeAccessVpnService.MSG_STOP, arg1 = 1)) {
                runCatching { appCtx.startService(FreeAccessVpnService.stopIntent(appCtx, handoff = true)) }
            }
            unbindFreeAccess(appCtx)
        }
        activeBackend = Backend.REGULAR
        freeAccessStage.value = null
        captcha.value = null
        state.value = State.Connecting
        traffic.value = Traffic.ZERO
        runCatching {
            ContextCompat.startForegroundService(appCtx, intent(appCtx, SelykVpnService.ACTION_START))
        }.onFailure { e ->
            Log.e(TAG, "Failed to start SelykVpnService", e)
            activeBackend = Backend.NONE
            state.value = State.Idle
            reportError(appCtx.getString(R.string.vpn_start_failed_fgs))
        }
    }

    fun startFreeAccess(
        context: Context,
        config: FreeAccessConfig,
        excludedApps: Set<String>,
        reconnect: Boolean = false,
        logEnabled: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (activeBackend == Backend.REGULAR) {
            runCatching {
                appCtx.startService(
                    intent(appCtx, SelykVpnService.ACTION_STOP).putExtra(SelykVpnService.EXTRA_HANDOFF, true)
                )
            }
        }
        VKCookieStore.init(appCtx)
        val validCookie = if (config.useSessionAuth && VKCookieStore.hasValidLogin()) {
            VKCookieStore.getValidCookieHeader()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val effectiveConfig = if (config.deviceId.isBlank()) {
            config.copy(deviceId = FreeAccessConfig.resolveStableDeviceId(appCtx))
        } else {
            config
        }

        val reqTime = SystemClock.elapsedRealtime()
        lastFreeAccessStartReqMs = reqTime
        activeBackend = Backend.FREE_ACCESS
        stopWatchdogJob?.cancel()
        syncWatchdogJob?.cancel()
        if (!reconnect) {
            state.value = State.Connecting
            traffic.value = Traffic.ZERO
            freeAccessStage.value = appCtx.getString(R.string.free_access_stage_vk)
            captcha.value = null
        }

        val started = runCatching {
            ContextCompat.startForegroundService(
                appCtx,
                FreeAccessVpnService.startIntent(
                    appCtx,
                    effectiveConfig,
                    validCookie,
                    excludedApps,
                    reconnect = reconnect,
                    logEnabled = logEnabled,
                ),
            )
        }
        if (started.isFailure) {
            Log.e(TAG, "Failed to start FreeAccessVpnService", started.exceptionOrNull())
            activeBackend = Backend.NONE
            state.value = State.Idle
            freeAccessStage.value = null
            captcha.value = null
            reportError(appCtx.getString(R.string.vpn_start_failed_fgs))
            return
        }

        bindFreeAccessIfAlive(appCtx)
        startWatchdogJob?.cancel()
        startWatchdogJob = controllerScope.launch {
            delay(START_WATCHDOG_MS)
            val binderAlive = synchronized(ipcLock) { remoteBinder?.isBinderAlive == true }
            if (activeBackend == Backend.FREE_ACCESS &&
                state.value is State.Connecting &&
                lastFreeAccessEventMs < reqTime &&
                !binderAlive
            ) {
                Log.e(TAG, "FreeAccessVpnService startup timed out without IPC response")
                recoverFromFreeAccessDeath(
                    unexpected = true,
                    errorMessage = appCtx.getString(R.string.free_access_start_timeout),
                )
                runCatching { appCtx.startService(FreeAccessVpnService.stopIntent(appCtx, handoff = false)) }
            } else if (!binderAlive && activeBackend == Backend.FREE_ACCESS && state.value !is State.Idle) {
                bindFreeAccessIfAlive(appCtx)
            }
        }
    }

    fun submitCaptcha(context: Context, answer: String) {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return
        val appCtx = context.applicationContext
        captcha.value = null
        val bundle = Bundle().apply { putString(FreeAccessVpnService.EXTRA_CAPTCHA_ANSWER, trimmed) }
        if (!sendToRemote(FreeAccessVpnService.MSG_SOLVE_CAPTCHA, data = bundle)) {
            runCatching {
                appCtx.startService(
                    Intent(appCtx, FreeAccessVpnService::class.java)
                        .setAction(FreeAccessVpnService.ACTION_SOLVE_CAPTCHA)
                        .putExtra(FreeAccessVpnService.EXTRA_CAPTCHA_ANSWER, trimmed)
                )
            }
        }
    }

    fun refreshCaptcha(context: Context) {
        val appCtx = context.applicationContext
        if (!sendToRemote(FreeAccessVpnService.MSG_REFRESH_CAPTCHA)) {
            runCatching {
                appCtx.startService(
                    Intent(appCtx, FreeAccessVpnService::class.java)
                        .setAction(FreeAccessVpnService.ACTION_REFRESH_CAPTCHA)
                )
            }
        }
    }

    fun cancelCaptcha(context: Context) {
        captcha.value = null
        stop(context)
    }

    fun setFreeAccessLogEnabled(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        sendToRemote(FreeAccessVpnService.MSG_SET_LOG_ENABLED, arg1 = if (enabled) 1 else 0)
    }

    fun start(context: Context, forceMode: String? = null) {
        val appCtx = context.applicationContext
        appContext = appCtx
        controllerScope.launch {
            val graph = SelykApp.graph
            val mode = forceMode ?: runCatching { graph.settings.activeMode.first() }.getOrDefault("regular")
            if (mode == "free_access") {
                val cfgStr = runCatching { graph.settings.freeAccessConfig.first() }.getOrNull()
                val cfg = cfgStr?.takeIf { it.isNotBlank() }?.let {
                    runCatching { AppJson.decodeFromString<FreeAccessConfig>(it) }.getOrNull()
                } ?: FreeAccessConfig()
                val excluded = runCatching { exclusionSettings(graph.settings, graph.remoteConfig).first().effective }
                    .getOrDefault(emptySet())
                val logEnabled = runCatching { graph.settings.connectionLogNow() }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    startFreeAccess(appCtx, cfg, excluded, reconnect = false, logEnabled = logEnabled)
                }
            } else {
                withContext(Dispatchers.Main) {
                    startRegular(appCtx)
                }
            }
        }
    }

    fun switchMode(context: Context, mode: String) {
        if (!isActive) return
        start(context, forceMode = mode)
    }

    fun stop(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (state.value == State.Idle && activeBackend == Backend.NONE) return
        startWatchdogJob?.cancel()
        syncWatchdogJob?.cancel()
        when (activeBackend) {
            Backend.FREE_ACCESS -> {
                if (state.value !is State.Idle) {
                    state.value = State.Stopping
                }
                freeAccessStage.value = null
                captcha.value = null
                val sentOverBinder = sendToRemote(FreeAccessVpnService.MSG_STOP, arg1 = 0)
                runCatching { appCtx.startService(FreeAccessVpnService.stopIntent(appCtx, handoff = false)) }
                if (!sentOverBinder && synchronized(ipcLock) { remoteBinder?.isBinderAlive != true }) {
                    // Remote service was not reachable; try stop watchdog so UI never locks on Stopping.
                    Log.d(TAG, "stop(): sent startService stop intent while unbound")
                }
                stopWatchdogJob?.cancel()
                stopWatchdogJob = controllerScope.launch {
                    delay(STOP_WATCHDOG_MS)
                    if (activeBackend == Backend.FREE_ACCESS && state.value is State.Stopping) {
                        Log.w(TAG, "Stop watchdog expired; forcing Idle state")
                        unbindFreeAccess(appCtx)
                        recoverFromFreeAccessDeath(unexpected = false)
                    }
                }
            }
            Backend.REGULAR, Backend.NONE -> {
                runCatching { appCtx.startService(intent(appCtx, SelykVpnService.ACTION_STOP)) }
            }
        }
    }

    /** Restarts the core with the currently selected mode/server. */
    fun reconnect(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (state.value !is State.Connected) return
        if (activeBackend == Backend.FREE_ACCESS) {
            controllerScope.launch {
                val graph = SelykApp.graph
                val cfgStr = runCatching { graph.settings.freeAccessConfig.first() }.getOrNull()
                val cfg = cfgStr?.takeIf { it.isNotBlank() }?.let {
                    runCatching { AppJson.decodeFromString<FreeAccessConfig>(it) }.getOrNull()
                } ?: return@launch
                val excluded = runCatching { exclusionSettings(graph.settings, graph.remoteConfig).first().effective }
                    .getOrDefault(emptySet())
                val logEnabled = runCatching { graph.settings.connectionLogNow() }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    startFreeAccess(appCtx, cfg, excluded, reconnect = true, logEnabled = logEnabled)
                }
            }
        } else {
            runCatching { appCtx.startService(intent(appCtx, SelykVpnService.ACTION_RECONNECT)) }
        }
    }

    private fun intent(context: Context, action: String) =
        Intent(context, SelykVpnService::class.java).setAction(action)
}
