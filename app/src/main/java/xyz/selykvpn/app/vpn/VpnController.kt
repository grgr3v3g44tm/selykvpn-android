package xyz.selykvpn.app.vpn

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.exclusionSettings
import xyz.selykvpn.app.model.FreeAccessConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide VPN state shared by the service, the UI and the Quick Settings tile. */
object VpnController {

    private const val TAG = "VpnController"

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

    val state = MutableStateFlow<State>(State.Idle)
    val traffic = MutableStateFlow(Traffic.ZERO)
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

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val filter = IntentFilter(FreeAccessVpnService.ACTION_STATUS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val faState = intent.getStringExtra(FreeAccessVpnService.EXTRA_STATE) ?: return
                val error = intent.getStringExtra(FreeAccessVpnService.EXTRA_ERROR)
                if (!error.isNullOrBlank()) {
                    reportError(error)
                }
                // Ignore stale Idle/Stopping broadcasts from :vkturn if user already switched to Regular mode.
                if (activeBackend == Backend.REGULAR &&
                    (faState == FreeAccessVpnService.STATE_IDLE || faState == FreeAccessVpnService.STATE_STOPPING)
                ) {
                    return
                }
                when (faState) {
                    FreeAccessVpnService.STATE_CONNECTING -> {
                        activeBackend = Backend.FREE_ACCESS
                        state.value = State.Connecting
                    }
                    FreeAccessVpnService.STATE_CONNECTED -> {
                        activeBackend = Backend.FREE_ACCESS
                        val since = intent.getLongExtra(FreeAccessVpnService.EXTRA_SINCE, System.currentTimeMillis())
                        state.value = State.Connected(since = since, serverKey = "free_access")
                        traffic.value = Traffic(
                            up = intent.getLongExtra(FreeAccessVpnService.EXTRA_UP_SPEED, 0L),
                            down = intent.getLongExtra(FreeAccessVpnService.EXTRA_DOWN_SPEED, 0L),
                            totalUp = intent.getLongExtra(FreeAccessVpnService.EXTRA_TOTAL_UP, 0L),
                            totalDown = intent.getLongExtra(FreeAccessVpnService.EXTRA_TOTAL_DOWN, 0L),
                        )
                    }
                    FreeAccessVpnService.STATE_STOPPING -> {
                        activeBackend = Backend.FREE_ACCESS
                        state.value = State.Stopping
                    }
                    FreeAccessVpnService.STATE_IDLE -> {
                        if (activeBackend == Backend.FREE_ACCESS || activeBackend == Backend.NONE) {
                            activeBackend = Backend.NONE
                            traffic.value = Traffic.ZERO
                            state.value = State.Idle
                        }
                    }
                }
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
        syncFreeAccessIfRunning(appCtx)
    }

    private fun syncFreeAccessIfRunning(context: Context) {
        val intent = Intent(context, FreeAccessVpnService::class.java).setAction(FreeAccessVpnService.ACTION_BIND_IPC)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching {
                    Messenger(service).send(Message.obtain(null, FreeAccessVpnService.MSG_REQUEST_SYNC))
                }.onFailure { Log.w(TAG, "Failed to request FreeAccess sync", it) }
                runCatching { context.unbindService(this) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching {
            // Flag 0 ensures Android never spawns :vkturn if FreeAccessVpnService isn't already running.
            context.bindService(intent, conn, 0)
        }
    }

    fun reportError(message: String) {
        _errors.tryEmit(message)
    }

    val isActive: Boolean get() = state.value is State.Connected || state.value is State.Connecting

    fun startRegular(context: Context) {
        if (activeBackend == Backend.FREE_ACCESS) {
            runCatching { context.startService(FreeAccessVpnService.stopIntent(context, handoff = true)) }
        }
        activeBackend = Backend.REGULAR
        state.value = State.Connecting
        traffic.value = Traffic.ZERO
        ContextCompat.startForegroundService(context, intent(context, SelykVpnService.ACTION_START))
    }

    fun startFreeAccess(
        context: Context,
        config: FreeAccessConfig,
        excludedApps: Set<String>,
        reconnect: Boolean = false,
    ) {
        if (activeBackend == Backend.REGULAR) {
            runCatching {
                context.startService(
                    intent(context, SelykVpnService.ACTION_STOP).putExtra(SelykVpnService.EXTRA_HANDOFF, true)
                )
            }
        }
        VKCookieStore.init(context.applicationContext)
        val validCookie = if (config.useSessionAuth && VKCookieStore.hasValidLogin()) {
            VKCookieStore.getValidCookieHeader()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        activeBackend = Backend.FREE_ACCESS
        if (!reconnect) {
            state.value = State.Connecting
            traffic.value = Traffic.ZERO
        }
        ContextCompat.startForegroundService(
            context,
            FreeAccessVpnService.startIntent(context, config, validCookie, excludedApps, reconnect = reconnect),
        )
    }

    fun start(context: Context, forceMode: String? = null) {
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
                withContext(Dispatchers.Main) {
                    startFreeAccess(context.applicationContext, cfg, excluded, reconnect = false)
                }
            } else {
                withContext(Dispatchers.Main) {
                    startRegular(context.applicationContext)
                }
            }
        }
    }

    fun switchMode(context: Context, mode: String) {
        if (!isActive) return
        start(context, forceMode = mode)
    }

    fun stop(context: Context) {
        if (state.value == State.Idle && activeBackend == Backend.NONE) return
        when (activeBackend) {
            Backend.FREE_ACCESS -> {
                runCatching { context.startService(FreeAccessVpnService.stopIntent(context, handoff = false)) }
            }
            Backend.REGULAR, Backend.NONE -> {
                runCatching { context.startService(intent(context, SelykVpnService.ACTION_STOP)) }
            }
        }
    }

    /** Restarts the core with the currently selected mode/server. */
    fun reconnect(context: Context) {
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
                withContext(Dispatchers.Main) {
                    startFreeAccess(context.applicationContext, cfg, excluded, reconnect = true)
                }
            }
        } else {
            runCatching { context.startService(intent(context, SelykVpnService.ACTION_RECONNECT)) }
        }
    }

    private fun intent(context: Context, action: String) =
        Intent(context, SelykVpnService::class.java).setAction(action)
}
