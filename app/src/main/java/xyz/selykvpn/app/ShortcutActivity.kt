package xyz.selykvpn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.selykvpn.app.vpn.VpnController

/**
 * App shortcuts "Вкл/выкл VPN", "Подключить", "Отключить" (res/xml/shortcuts.xml), without opening
 * the app. Samsung Modes and Routines ("open an app or do an app action"), the Infinix action
 * button and launchers start them on the app's behalf, so the activity isn't exported.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_TOGGLE -> if (VpnController.isActive) disconnect() else connect()
            ACTION_CONNECT -> if (!VpnController.isActive) connect()
            ACTION_DISCONNECT -> if (VpnController.isActive) disconnect()
        }
        finish()
    }

    private fun connect() {
        if (VpnService.prepare(this) != null) {
            openAppForConnect()
            return
        }
        val graph = SelykApp.graph
        graph.scope.launch(Dispatchers.IO) {
            val mode = VpnController.quickStartModeIfReady(graph)
            withContext(Dispatchers.Main) {
                if (mode == null) {
                    openAppForConnect()
                } else {
                    val appCtx = applicationContext
                    VpnController.start(
                        appCtx,
                        forceMode = mode,
                        onFgsDenied = {
                            graph.scope.launch(Dispatchers.Main) { openAppForConnect(appCtx) }
                        },
                    )
                    Toast.makeText(appCtx, R.string.shortcut_connecting, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openAppForConnect(ctx: android.content.Context = this) {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CONNECT, true)
                .putExtra(MainActivity.EXTRA_CONNECT_TOKEN, SelykApp.internalConnectToken),
        )
    }

    private fun disconnect() {
        VpnController.stop(this)
        Toast.makeText(this, R.string.shortcut_disconnected, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_TOGGLE = "xyz.selykvpn.app.action.TOGGLE"
        const val ACTION_CONNECT = "xyz.selykvpn.app.action.CONNECT"
        const val ACTION_DISCONNECT = "xyz.selykvpn.app.action.DISCONNECT"
    }
}
