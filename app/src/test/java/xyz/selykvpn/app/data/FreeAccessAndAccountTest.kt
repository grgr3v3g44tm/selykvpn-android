package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FreeAccessAndAccountTest {

    @Test
    fun defaultsUseTcpAndWrapA() {
        val cfg = FreeAccessConfig()
        assertEquals(TunnelMode.SRTP_WRAP_A, cfg.mode)
        assertFalse(cfg.useUDP)
        assertFalse(cfg.useSessionAuth)
        assertFalse(cfg.hasRealVKCallLink())
    }

    @Test
    fun snakeCaseAndWrapAPasswordParsing() {
        val json = """
            {
              "settings": {
                "name": "FI Gateway",
                "peer_addr": "media-stream.xamudhabibi.qzz.io:56000",
                "use_wrap_a": true,
                "wrap_a_password": "p_super_secret",
                "use_cookie_auth": true,
                "vk_link": "https://vk.ru/call/join/validCall123\nhttps://vk.com/call/join/secondCall",
                "num_conns": 30
              }
            }
        """.trimIndent()
        val cfg = FreeAccessConfig.parse(json)
        assertNotNull(cfg)
        cfg!!
        assertEquals(TunnelMode.SRTP_WRAP_A, cfg.mode)
        assertEquals("p_super_secret", cfg.serverPassword)
        assertTrue(cfg.useSessionAuth)
        assertFalse(cfg.useUDP)
        assertTrue(cfg.hasRealVKCallLink())
        // Two call links -> cap is (2 * 20).coerceIn(2, 50) = 40, minOf(30, 40) = 30
        assertEquals(30, cfg.effectiveNumConnections)

        val singleLink = cfg.copy(stealthLink = "https://vk.ru/call/join/onlyOne")
        // One call link with useSessionAuth=true -> cap is 20, minOf(30, 20) = 20
        assertEquals(20, singleLink.effectiveNumConnections)

        val proxyJson = singleLink.toProxyConfigJson()
        assertTrue(proxyJson.contains(""""use_udp":false"""))
        assertTrue(proxyJson.contains(""""use_cookie_auth":true"""))
        assertTrue(proxyJson.contains(""""wrap_a_password":"p_super_secret""""))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun mergeFromRemotePreservesLocalCallAndVkToggle() {
        val local = FreeAccessConfig(
            stealthLink = "https://vk.ru/call/join/userCall999",
            needsStealthLink = false,
            serverPassword = "",
            useSessionAuth = true,
        )
        val payloadJson = """
            {
              "type": "connection",
              "version": 1,
              "settings": {
                "serverName": "Селюк VPN",
                "peerAddress": "media-stream.xamudhabibi.qzz.io:56000",
                "wrapAPassword": "p_from_server",
                "serverPassword": "p_from_server",
                "useWrapA": true,
                "vkLink": "vkturnproxy://placeholder"
              }
            }
        """.trimIndent()
        val encoded = Base64.UrlSafe.encode(payloadJson.toByteArray()).trimEnd('=')
        val remote = FreeAccessConfig.parse("vkturnproxy://import?data=$encoded")
        assertNotNull(remote)

        val merged = local.mergeFromRemote(remote!!)
        assertEquals("p_from_server", merged.serverPassword)
        assertEquals("https://vk.ru/call/join/userCall999", merged.stealthLink)
        assertFalse(merged.needsStealthLink)
        assertTrue(merged.useSessionAuth)
        assertFalse(merged.useUDP)
    }

    @Test
    fun accountStatusParsesServerStringPlanAndIsoExpiry() {
        val raw = """
            {
              "found": true,
              "planId": "selyk_whitelist",
              "planName": "Селюк Про (Свободный доступ)",
              "expiresAt": "2026-12-31T23:59:59.000Z",
              "isFree": false,
              "status": "ACTIVE",
              "trafficUsed": 1024,
              "trafficLimit": 0,
              "isWhitelist": true,
              "vkturnUri": "vkturnproxy://import?data=abc",
              "vkturnPassword": "p_abc",
              "tunnelIp": "10.77.77.2/24"
            }
        """.trimIndent()
        val parsed = AccountRepository.parseResponse(raw, fetchedAt = 123L)
        assertTrue(parsed.found)
        assertEquals("selyk_whitelist", parsed.planId)
        assertEquals("Селюк Про (Свободный доступ)", parsed.planName)
        assertEquals(1798761599L, parsed.expiresAt)
        assertTrue(parsed.isWhitelist)
        assertEquals("vkturnproxy://import?data=abc", parsed.vkturnUri)
        assertEquals("p_abc", parsed.vkturnPassword)
        assertEquals(123L, parsed.fetchedAt)
    }
}
