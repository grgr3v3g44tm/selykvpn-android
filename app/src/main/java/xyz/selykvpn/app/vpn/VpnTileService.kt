package xyz.selykvpn.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.MainActivity
import xyz.selykvpn.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile: one tap to connect or disconnect. */
class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        VpnController.syncState(this)
        job?.cancel()
        job = serviceScope.launch {
            VpnController.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (clickJob?.isActive == true) return
        if (VpnController.isActive) {
            VpnController.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        clickJob = serviceScope.launch(Dispatchers.IO) {
            val mode = VpnController.quickStartModeIfReady(SelykApp.graph)
            withContext(Dispatchers.Main) {
                if (mode == null) {
                    openApp()
                } else {
                    VpnController.start(
                        this@VpnTileService,
                        forceMode = mode,
                        onFgsDenied = { serviceScope.launch(Dispatchers.Main) { openApp() } },
                    )
                }
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONNECT, true)
            .putExtra(MainActivity.EXTRA_CONNECT_TOKEN, SelykApp.internalConnectToken)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: VpnController.State) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            is VpnController.State.Connected -> Tile.STATE_ACTIVE
            VpnController.State.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (state) {
                    is VpnController.State.Connected -> R.string.status_connected
                    VpnController.State.Connecting -> R.string.status_connecting
                    else -> R.string.status_disconnected
                },
            )
        }
        tile.updateTile()
    }
}
