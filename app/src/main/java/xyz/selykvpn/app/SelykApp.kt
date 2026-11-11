package xyz.selykvpn.app

import android.app.Application
import xyz.selykvpn.app.data.AccountRepository
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.RemoteConfigRepository
import xyz.selykvpn.app.data.SettingsStore
import xyz.selykvpn.app.data.SubscriptionRepository
import xyz.selykvpn.app.data.TvPairing
import xyz.selykvpn.app.data.WhitelistMonitor
import xyz.selykvpn.app.vpn.Notifications
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
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.whitelist.start()
        graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val xrayDir = java.io.File(filesDir, "xray").apply { mkdirs() }
            xyz.selykvpn.app.vpn.Xray.ensureGeoFiles(this@SelykApp, xrayDir)
        }
    }

    companion object {
        lateinit var instance: SelykApp
            private set

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
    val whitelist = WhitelistMonitor(app, http, settings, remoteConfig, subscriptions, scope)
}
