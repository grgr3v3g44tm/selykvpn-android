package xyz.selykvpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import xyz.selykvpn.app.R
import xyz.selykvpn.libxray.ConnectionListener
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug log of the session (Settings → Журнал подключений, off by default): which addresses apps
 * open and whether each connection goes through the VPN, directly or is blocked. Kept in memory
 * until the next connection; apps excluded from the VPN never reach it.
 */
object ConnectionLog {
    enum class Route { VPN, DIRECT, BLOCKED }

    /** One address of one app: how many connections and the latest one's details. */
    data class Entry(
        val host: String,
        val port: Int,
        val udp: Boolean,
        val route: Route,
        val count: Int,
        val firstSeen: Long,
        val lastSeen: Long,
    )

    data class App(val key: String, val packageName: String?, val label: String, val entries: List<Entry>, val firstSeen: Long)

    const val MAX_ENTRIES = 3000

    private data class EntryKey(val app: String, val host: String, val route: Route)
    private data class AppInfo(val packageName: String?, val label: String)

    private val lock = Any()
    private val entries = HashMap<EntryKey, Entry>()
    private val apps = HashMap<String, AppInfo>()

    /** Grows with every change, so the screen redraws only when there is something new. */
    @Volatile
    var version = 0L
        private set

    /** Where each outbound of the running config leads; DNS answers are not connections. */
    @Volatile
    private var routes: Map<String, Route?> = emptyMap()

    fun setOutbounds(protocols: Map<String, String>) {
        routes = protocols.mapValues { (_, protocol) ->
            when (protocol) {
                "freedom" -> Route.DIRECT
                "blackhole" -> Route.BLOCKED
                "dns", "loopback" -> null
                else -> Route.VPN
            }
        }
    }

    /** Unknown tags are the panel's proxies (balancers pick them by tag). */
    fun routeOf(outbound: String): Route? = if (outbound in routes) routes[outbound] else Route.VPN

    fun record(appKey: String, packageName: String?, label: String, host: String, port: Int, udp: Boolean, route: Route, time: Long) {
        synchronized(lock) {
            apps.getOrPut(appKey) { AppInfo(packageName, label) }
            val key = EntryKey(appKey, host, route)
            val old = entries[key]
            entries[key] = Entry(host, port, udp, route, (old?.count ?: 0) + 1, old?.firstSeen ?: time, time)
            if (entries.size > MAX_ENTRIES) {
                // Forget the oldest tenth.
                entries.entries.sortedBy { it.value.lastSeen }.take(MAX_ENTRIES / 10).forEach { entries.remove(it.key) }
                val used = entries.keys.mapTo(HashSet()) { it.app }
                apps.keys.retainAll(used)
            }
            version++
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            apps.clear()
            version++
        }
    }

    /**
     * Apps and their addresses in the order they first appeared: new ones are added at the end,
     * so the list doesn't jump under the finger while it updates.
     */
    fun snapshot(): List<App> = synchronized(lock) {
        entries.entries.groupBy({ it.key.app }, { it.value }).map { (key, list) ->
            val info = apps[key]
            App(key, info?.packageName, info?.label ?: key, list.sortedBy { it.firstSeen }, list.minOf { it.firstSeen })
        }
    }.sortedBy { it.firstSeen }

    /**
     * Receives new connections from the core and finds the app behind each one. Called right on
     * the core's connection path while the socket is still open, so it must stay quick.
     */
    fun listener(context: Context): ConnectionListener = Resolver(context.applicationContext)

    private class Resolver(private val context: Context) : ConnectionListener {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private val appsByUid = ConcurrentHashMap<Int, Triple<String, String?, String>>()

        override fun onConnection(
            network: String,
            srcIP: String,
            srcPort: Int,
            dstIP: String,
            dstPort: Int,
            domain: String,
            inbound: String,
            outbound: String,
        ) {
            val route = routeOf(outbound) ?: return
            val host = domain.ifEmpty { dstIP }.ifEmpty { return }
            val udp = network == "udp"
            val (key, pkg, label) = when (inbound) {
                XrayConfigBuilder.TUN_TAG -> app(ownerUid(udp, srcIP, srcPort, dstIP, dstPort))
                XrayConfigBuilder.APP_PROXY_TAG -> app(Process.myUid())
                XrayConfigBuilder.SHARE_HTTP_TAG, XrayConfigBuilder.SHARE_SOCKS_TAG ->
                    Triple("share:$srcIP", null, context.getString(R.string.connlog_share_device, srcIP))
                else -> return
            }
            record(key, pkg, label, host, dstPort, udp, route, System.currentTimeMillis())
        }

        /** The app whose socket this is, or -1 when it is already closed. */
        private fun ownerUid(udp: Boolean, srcIP: String, srcPort: Int, dstIP: String, dstPort: Int): Int {
            if (srcIP.isEmpty() || dstIP.isEmpty()) return -1
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ProcNet.uid(udp, srcIP, srcPort)
            val protocol = if (udp) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            val remote = InetSocketAddress(InetAddress.getByName(dstIP), dstPort)
            fun lookup(local: String) = runCatching {
                connectivity!!.getConnectionOwnerUid(protocol, InetSocketAddress(InetAddress.getByName(local), srcPort), remote)
            }.getOrDefault(-1)
            val uid = lookup(srcIP)
            if (uid >= 0 || !udp) return uid
            // Unconnected UDP sockets (QUIC in many apps) are bound to the wildcard address.
            return listOf("::", "0.0.0.0").firstNotNullOfOrNull { any -> lookup(any).takeIf { it >= 0 } } ?: -1
        }

        private fun app(uid: Int): Triple<String, String?, String> {
            // 0 is what a socket that has just been closed reports (TIME_WAIT has no owner).
            if (uid <= 0) return Triple("unknown", null, context.getString(R.string.connlog_unknown_app))
            return appsByUid.getOrPut(uid) {
                val pm = context.packageManager
                val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty().sorted()
                when {
                    uid == Process.myUid() -> Triple("uid:$uid", context.packageName, context.getString(R.string.app_name))
                    uid == Process.SYSTEM_UID -> Triple("uid:$uid", null, context.getString(R.string.connlog_system))
                    // System services without a package, e.g. the DNS resolver (1051).
                    packages.isEmpty() && uid < Process.FIRST_APPLICATION_UID ->
                        Triple("uid:$uid", null, context.getString(R.string.connlog_system) + " · UID $uid")
                    packages.isEmpty() -> Triple("uid:$uid", null, "UID $uid")
                    else -> {
                        val pkg = packages.first()
                        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                        Triple("uid:$uid", pkg, if (packages.size > 1) "$label +${packages.size - 1}" else label)
                    }
                }
            }
        }
    }

    /** Android 7–9: the owner of a socket from /proc/net (closed to apps since Android 10). */
    private object ProcNet {
        private val spaces = Regex("\\s+")

        fun uid(udp: Boolean, srcIP: String, srcPort: Int): Int {
            val bytes = runCatching { InetAddress.getByName(srcIP).address }.getOrNull() ?: return -1
            // Addresses are printed as 32-bit words in little-endian order; IPv4 ones in the "6"
            // files are mapped (::ffff:a.b.c.d), so they end the same way.
            val local = buildString {
                for (word in bytes.indices step 4) for (i in 3 downTo 0) append("%02X".format(bytes[word + i]))
            } + ":%04X".format(srcPort)
            val files = if (udp) listOf("/proc/net/udp", "/proc/net/udp6") else listOf("/proc/net/tcp", "/proc/net/tcp6")
            for (path in files) {
                val uid = runCatching {
                    File(path).useLines { lines ->
                        lines.drop(1).map { it.trim().split(spaces) }
                            .firstOrNull { it.size > 7 && it[1].endsWith(local) }
                            ?.get(7)?.toIntOrNull()
                    }
                }.getOrNull()
                if (uid != null) return uid
            }
            return -1
        }
    }
}
