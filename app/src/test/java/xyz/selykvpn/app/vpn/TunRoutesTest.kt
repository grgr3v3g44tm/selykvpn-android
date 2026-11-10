package xyz.selykvpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress

class TunRoutesTest {
    private fun inRoutes(ip: String, routes: List<TunRoutes.Route>): Boolean {
        val bytes = InetAddress.getByName(ip).address
        val bits = bytes.size * 8
        val value = BigInteger(1, bytes)
        return routes.any { r ->
            val net = BigInteger(1, InetAddress.getByName(r.address).address)
            value.shiftRight(bits - r.prefix) == net.shiftRight(bits - r.prefix)
        }
    }

    @Test
    fun lanAndMulticastStayOutOfTheTunnel() {
        val v4 = TunRoutes.ipv4(lanDirect = true)
        for (local in listOf("192.168.1.50", "10.0.0.1", "172.16.5.5", "172.31.255.255", "169.254.1.1", "224.0.0.251", "239.255.255.250", "255.255.255.255")) {
            assertFalse(local, inRoutes(local, v4))
        }
        for (internet in listOf("8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.64.0.1", "223.255.255.255", "77.88.8.8")) {
            assertTrue(internet, inRoutes(internet, v4))
        }
        val v6 = TunRoutes.ipv6(lanDirect = true)
        for (local in listOf("fe80::1", "fd00::1", "ff02::fb")) assertFalse(local, inRoutes(local, v6))
        for (internet in listOf("2a00:1450:4010::1", "2001:4860:4860::8888")) assertTrue(internet, inRoutes(internet, v6))
    }

    @Test
    fun fewRoutes() {
        // The smallest split of the address space around the local ranges: a few dozen prefixes.
        assertTrue(TunRoutes.ipv4(lanDirect = true).size < 50)
        assertTrue(TunRoutes.ipv6(lanDirect = true).size < 20)
        assertEquals(listOf(TunRoutes.Route("0.0.0.0", 0)), TunRoutes.ipv4(lanDirect = false))
        assertEquals(listOf(TunRoutes.Route("::", 0)), TunRoutes.ipv6(lanDirect = false))
    }
}
