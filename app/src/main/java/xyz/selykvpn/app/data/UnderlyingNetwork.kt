package xyz.selykvpn.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.OsConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The phone's real network (Wi-Fi, mobile, Ethernet). For the VPN app itself the system's "default
 * network" is its own VPN while it runs, so the default-network callback never sees Wi-Fi ↔ mobile
 * switches; this watches the non-VPN networks and picks the one the phone uses: a validated one
 * first, Wi-Fi and Ethernet before mobile data.
 */
object UnderlyingNetwork {

    /** [key] changes when the network or its address changes. */
    data class State(val key: String, val kind: NetworkKind)

    fun changes(context: Context): Flow<State?> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        // Callbacks arrive one at a time on the system's connectivity thread.
        val networks = HashMap<Network, Pair<NetworkCapabilities?, LinkProperties?>>()
        fun publish() {
            val best = networks.entries.minByOrNull { rank(it.value.first) }
            trySend(best?.let { state(it.key, it.value.first, it.value.second) })
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networks[network] = caps to networks[network]?.second
                publish()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                networks[network] = networks[network]?.first to lp
                publish()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                publish()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
        if (!registered) trySend(current(context))
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    /** The same choice right now, without waiting for callbacks. */
    @Suppress("DEPRECATION") // allNetworks: there is no other synchronous way to list them
    fun current(context: Context): State? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            cm.allNetworks
                .mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
                .filter { (_, caps) ->
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                .minByOrNull { (_, caps) -> rank(caps) }
                ?.let { (network, caps) -> state(network, caps, cm.getLinkProperties(network)) }
        }.getOrNull()
    }

    private fun state(network: Network, caps: NetworkCapabilities?, lp: LinkProperties?): State {
        // Rotating IPv6 privacy addresses don't break connections.
        val addresses = lp?.linkAddresses.orEmpty()
            .filter { it.flags and OsConstants.IFA_F_TEMPORARY == 0 && !it.address.isLinkLocalAddress }
            .map { it.address.hostAddress }
            .sorted()
        return State("$network $addresses", kindOf(caps))
    }

    private fun rank(caps: NetworkCapabilities?): Int {
        if (caps == null) return 100
        val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 10
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 2
        }
        return validated + transport
    }

    fun kindOf(caps: NetworkCapabilities?): NetworkKind = when {
        caps == null -> NetworkKind.NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
        else -> NetworkKind.OTHER
    }
}
