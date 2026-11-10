package xyz.selykvpn.app.vpn

import xyz.selykvpn.app.vpn.ConnectionLog.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionLogTest {

    @Before
    fun reset() {
        ConnectionLog.clear()
        ConnectionLog.setOutbounds(mapOf("proxy" to "vless", "direct" to "freedom", "block" to "blackhole", "dns-out" to "dns"))
    }

    @Test
    fun routesFollowOutboundProtocols() {
        assertEquals(Route.VPN, ConnectionLog.routeOf("proxy"))
        assertEquals(Route.DIRECT, ConnectionLog.routeOf("direct"))
        assertEquals(Route.BLOCKED, ConnectionLog.routeOf("block"))
        assertNull(ConnectionLog.routeOf("dns-out"))
        // Picked by a balancer, not listed by protocol: still a proxy.
        assertEquals(Route.VPN, ConnectionLog.routeOf("auto-3"))
    }

    @Test
    fun connectionsAreGroupedByAppAndAddress() {
        ConnectionLog.record("uid:10100", "org.telegram.messenger", "Telegram", "api.telegram.org", 443, false, Route.VPN, 1_000)
        ConnectionLog.record("uid:10100", "org.telegram.messenger", "Telegram", "api.telegram.org", 443, false, Route.VPN, 2_000)
        ConnectionLog.record("uid:10200", "ru.app", "App", "ya.ru", 443, true, Route.DIRECT, 3_000)

        // In the order of appearance, so the list doesn't jump while it updates.
        val apps = ConnectionLog.snapshot()
        assertEquals(listOf("Telegram", "App"), apps.map { it.label })
        val telegram = apps.first().entries.single()
        assertEquals(2, telegram.count)
        assertEquals(1_000, telegram.firstSeen)
        assertEquals(2_000, telegram.lastSeen)
        assertTrue(apps.last().entries.single().udp)
    }

    @Test
    fun oldestAddressesAreForgotten() {
        repeat(ConnectionLog.MAX_ENTRIES + 1) { i ->
            ConnectionLog.record("uid:1", null, "App", "host$i.example", 443, false, Route.VPN, i.toLong())
        }
        val entries = ConnectionLog.snapshot().single().entries
        assertTrue(entries.size <= ConnectionLog.MAX_ENTRIES)
        assertEquals("host${ConnectionLog.MAX_ENTRIES}.example", entries.last().host)
        assertTrue(entries.none { it.host == "host0.example" })
    }
}
