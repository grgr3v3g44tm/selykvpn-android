package com.vkturnproxy.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.vkturnproxy.R
import com.vkturnproxy.VkTurnApp
import com.vkturnproxy.model.hasValidVKCallLink
import com.vkturnproxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VkTurnTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()

        listeningJob = serviceScope.launch {
            try {
                val serverRepo = VkTurnApp.instance.serverRepository
                combine(
                    VkTurnVpnService.statusFlow,
                    serverRepo.activeServerFlow
                ) { status, activeServer ->
                    Pair(status, activeServer)
                }.collect { (status, activeServer) ->
                    updateTileState(status, activeServer?.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting state in tile: ${e.message}", e)
                updateTileState(VkTurnVpnService.statusFlow.value, null)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onClick() {
        super.onClick()
        val currentStatus = VkTurnVpnService.statusFlow.value
        Log.i(TAG, "Quick Settings tile clicked with current status: $currentStatus")

        when (currentStatus) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING, VpnStatus.WAITING_TURN -> {
                VkTurnVpnService.stop(this)
            }
            VpnStatus.DISCONNECTED, VpnStatus.ERROR -> {
                val vpnPrepareIntent = VpnService.prepare(this)
                if (vpnPrepareIntent == null) {
                    // VPN permission already granted, connect directly in background
                    val activeServer = try {
                        VkTurnApp.instance.serverRepository.activeServerFlow.value
                            ?: VkTurnApp.instance.serverRepository.serversFlow.value.firstOrNull()
                    } catch (_: Exception) {
                        null
                    }

                    if (activeServer != null && activeServer.hasValidVKCallLink()) {
                        VkTurnVpnService.start(this, activeServer)
                    } else {
                        launchMainActivity()
                    }
                } else {
                    // Permission dialog needed, open MainActivity
                    launchMainActivity()
                }
            }
        }
    }

    private fun updateTileState(status: VpnStatus, activeServerName: String?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)

        when (status) {
            VpnStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serverName = VkTurnVpnService.currentServerName.ifEmpty {
                        activeServerName ?: getString(R.string.qs_tile_subtitle_connected)
                    }
                    tile.subtitle = serverName
                }
            }
            VpnStatus.CONNECTING, VpnStatus.WAITING_TURN -> {
                tile.state = Tile.STATE_UNAVAILABLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_connecting)
                }
            }
            VpnStatus.DISCONNECTED, VpnStatus.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = activeServerName ?: getString(R.string.qs_tile_subtitle_disconnected)
                }
            }
        }

        tile.updateTile()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "VkTurnTileService"
    }
}
