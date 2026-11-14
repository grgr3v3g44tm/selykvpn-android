package xyz.selykvpn.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.data.AccountRepository
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.RemoteConfigRepository
import xyz.selykvpn.app.data.SettingsStore
import xyz.selykvpn.app.data.SubscriptionRepository
import xyz.selykvpn.app.data.TvPairing
import xyz.selykvpn.app.data.WhitelistMonitor
import xyz.selykvpn.app.vpn.Notifications
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SelykApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        if (isVkTurnProcess()) {
            // Isolated :vkturn process hosts FreeAccessVpnService + libwgturn.so only.
            // Skip DataStore/AppGraph/Xray initialization to avoid multi-process conflicts and dual Go runtimes.
            return
        }
        VpnController.init(this)
        graph = AppGraph(this)
        graph.whitelist.start()
        graph.scope.launch(Dispatchers.IO) {
            VKCookieStore.init(this@SelykApp)
            val xrayDir = java.io.File(filesDir, "xray").apply { mkdirs() }
            xyz.selykvpn.app.vpn.Xray.ensureGeoFiles(this@SelykApp, xrayDir)
        }
    }

    private fun isVkTurnProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
        return processName?.endsWith(":vkturn") == true
    }

    companion object {
        lateinit var instance: SelykApp
            private set

        val internalConnectToken: String = java.util.UUID.randomUUID().toString()

        val graph: AppGraph get() = instance.graph
    }
}

/** Manual dependency container: the app is small enough not to need a DI framework. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = Http()
    val settings = SettingsStore(app)
    val remoteConfig = RemoteConfigRepository(app, http)
    val subscriptions = SubscriptionRepository(app, http, remoteConfig, settings)
    val account = AccountRepository(app, http)
    val tvPairing = TvPairing(http)
    val whitelist = WhitelistMonitor(app, http, settings, remoteConfig, subscriptions, account, scope)
}
