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
    fun mergeFromRemotePreservesLocalWireGuardFieldsAndName() {
        val local = FreeAccessConfig(
            name = "Custom Gateway",
            stealthLink = "https://vk.ru/call/join/localCall1",
            privateKey = "privKeyLocal==",
            peerPublicKey = "peerPubLocal==",
            presharedKey = "pskLocal==",
            tunnelAddress = "10.77.77.5/24",
            deviceId = "dev-local-1",
            useSessionAuth = true,
        )
        val remote = FreeAccessConfig(
            name = "",
            stealthLink = "",
            privateKey = "",
            peerPublicKey = "",
            presharedKey = "",
            tunnelAddress = "",
            deviceId = "",
            serverPassword = "srv-pass-new",
            useSessionAuth = false,
        )
        val merged = local.mergeFromRemote(remote)
        assertEquals("Custom Gateway", merged.name)
        assertEquals("privKeyLocal==", merged.privateKey)
        assertEquals("peerPubLocal==", merged.peerPublicKey)
        assertEquals("pskLocal==", merged.presharedKey)
        assertEquals("10.77.77.5/24", merged.tunnelAddress)
        assertEquals("dev-local-1", merged.deviceId)
        assertEquals("srv-pass-new", merged.serverPassword)
        assertEquals("https://vk.ru/call/join/localCall1", merged.stealthLink)
        assertFalse(merged.needsStealthLink)
        assertTrue(merged.useSessionAuth)

        val updated = merged.withCallLink("  https://vk.ru/call/join/newCall2 \nother line ")
        assertEquals("https://vk.ru/call/join/newCall2", updated.stealthLink)
        assertFalse(updated.needsStealthLink)
        assertFalse(updated.useSessionAuth)
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

    @Test
    fun tariffAccessRulesAndCredentialCleanup() {
        val readySrtp = FreeAccessConfig(
            mode = TunnelMode.SRTP_WRAP_A,
            serverPassword = "secret",
            stealthLink = "https://vk.ru/call/join/valid123",
            needsStealthLink = false,
        )
        assertTrue(readySrtp.hasGatewayCredentials())
        assertTrue(readySrtp.isReadyForTunnel())

        val cleared = readySrtp.clearGatewayCredentials()
        assertFalse(cleared.hasGatewayCredentials())
        assertFalse(cleared.isReadyForTunnel())

        // Standalone vkturnproxy:// without subscription is always allowed.
        assertTrue(TariffAccess.isFreeAccessAllowed(hasSubscription = false, account = null, freeAccessConfig = FreeAccessConfig()))

        // Subscription with known whitelist vs standard tariff.
        assertTrue(
            TariffAccess.isFreeAccessAllowed(
                hasSubscription = true,
                account = AccountStatus(found = true, isWhitelist = true),
                freeAccessConfig = FreeAccessConfig(),
            ),
        )
        assertFalse(
            TariffAccess.isFreeAccessAllowed(
                hasSubscription = true,
                account = AccountStatus(found = true, isWhitelist = false),
                freeAccessConfig = readySrtp,
            ),
        )

        // Subscription with offline/unknown account falls back to gateway credentials presence.
        assertTrue(
            TariffAccess.isFreeAccessAllowed(
                hasSubscription = true,
                account = null,
                freeAccessConfig = readySrtp,
            ),
        )
        assertFalse(
            TariffAccess.isFreeAccessAllowed(
                hasSubscription = true,
                account = AccountStatus(found = true, isWhitelist = true, vkturnDeviceLimitReached = true),
                freeAccessConfig = readySrtp,
            ),
        )
        assertFalse(
            TariffAccess.isFreeAccessAllowed(
                hasSubscription = true,
                account = AccountStatus(found = false),
                freeAccessConfig = FreeAccessConfig(),
            ),
        )
    }

    @Test
    fun accountStatusParsesVkturnDeviceLimitFields() {
        val raw = """
            {
              "found": true,
              "planId": "selyk_whitelist",
              "isWhitelist": true,
              "vkturnDeviceLimitReached": true,
              "vkturnSlotsLimit": 1,
              "vkturnSlotsUsed": 1
            }
        """.trimIndent()
        val parsed = AccountRepository.parseResponse(raw, fetchedAt = 456L)
        assertTrue(parsed.vkturnDeviceLimitReached)
        assertEquals(1, parsed.vkturnSlotsLimit)
        assertEquals(1, parsed.vkturnSlotsUsed)
        assertEquals(null, parsed.vkturnUri)
    }

    @Test
    fun freeAccessConfigDeterministicIdAndCleanUapi() {
        val a = FreeAccessConfig(peerAddress = "144.31.226.239:56000")
        val b = FreeAccessConfig(peerAddress = "144.31.226.239:56000")
        assertEquals("default", a.id)
        assertEquals(a.id, b.id)

        val keyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val wgCfg = FreeAccessConfig(
            mode = TunnelMode.SRTP,
            privateKey = "$keyB64\n",
            peerPublicKey = "  $keyB64\r\n",
            peerAddress = "144.31.226.239:56000\n",
            stealthLink = "https://vk.ru/call/join/test",
        )
        val uapi = wgCfg.toUAPIConfig()
        assertTrue(uapi.contains("endpoint=144.31.226.239:56000\n"))
        assertFalse(uapi.contains("\r"))
    }
}
