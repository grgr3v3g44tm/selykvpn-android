package xyz.selykvpn.app.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-wide VPN state shared by the service, the UI and the Quick Settings tile. */
object VpnController {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val since: Long, val serverKey: String) : State
        data object Stopping : State
    }

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

    fun reportError(message: String) {
        _errors.tryEmit(message)
    }

    val isActive: Boolean get() = state.value is State.Connected || state.value is State.Connecting

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, intent(context, SelykVpnService.ACTION_START))
    }

    fun stop(context: Context) {
        if (state.value == State.Idle) return
        runCatching { context.startService(intent(context, SelykVpnService.ACTION_STOP)) }
    }

    /** Restarts the core with the currently selected server (used after switching servers). */
    fun reconnect(context: Context) {
        if (state.value !is State.Connected) return
        runCatching { context.startService(intent(context, SelykVpnService.ACTION_RECONNECT)) }
    }

    private fun intent(context: Context, action: String) =
        Intent(context, SelykVpnService::class.java).setAction(action)
}
