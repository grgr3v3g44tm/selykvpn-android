package xyz.selykvpn.app.vpn

import java.math.BigInteger
import java.net.InetAddress

/**
 * What the tunnel takes: everything, or with "LAN directly" on (Settings → Routing, on by default)
 * everything but the local network. Leaving it out of the routes, not only sending it direct in
 * Xray, keeps multicast and broadcast on the real Wi-Fi: device discovery (the Google TV remote,
 * Chromecast, DLNA, printers) and connections to devices at home work as without the VPN.
 */
object TunRoutes {
    data class Route(val address: String, val prefix: Int)

    /** Home networks, link-local, multicast and the reserved block with the broadcast address. */
    private val LOCAL_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4")
    private val LOCAL_V6 = listOf("fc00::/7", "fe80::/10", "ff00::/8")

    fun ipv4(lanDirect: Boolean): List<Route> = if (lanDirect) complement(32, LOCAL_V4) else listOf(Route("0.0.0.0", 0))

    fun ipv6(lanDirect: Boolean): List<Route> = if (lanDirect) complement(128, LOCAL_V6) else listOf(Route("::", 0))

    /** The whole address space without [excluded], in as few prefixes as possible. */
    internal fun complement(bits: Int, excluded: List<String>): List<Route> {
        val local = excluded.map(::parse)
        val routes = mutableListOf<Route>()
        fun walk(network: BigInteger, length: Int) {
            if (local.any { (address, len) -> len <= length && sameTop(network, address, len, bits) }) return
            if (local.none { (address, len) -> len > length && sameTop(network, address, length, bits) }) {
                routes += Route(format(network, bits), length)
                return
            }
            walk(network, length + 1)
            walk(network.setBit(bits - length - 1), length + 1)
        }
        walk(BigInteger.ZERO, 0)
        return routes
    }

    private fun sameTop(a: BigInteger, b: BigInteger, length: Int, bits: Int) =
        a.shiftRight(bits - length) == b.shiftRight(bits - length)

    private fun parse(cidr: String): Pair<BigInteger, Int> {
        val (ip, length) = cidr.split('/')
        return BigInteger(1, InetAddress.getByName(ip).address) to length.toInt()
    }

    private fun format(value: BigInteger, bits: Int): String {
        val bytes = ByteArray(bits / 8)
        val raw = value.toByteArray().takeLast(bytes.size)
        raw.forEachIndexed { i, b -> bytes[bytes.size - raw.size + i] = b }
        return InetAddress.getByAddress(bytes).hostAddress!!
    }
}
