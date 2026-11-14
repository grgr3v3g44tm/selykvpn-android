package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyParserTest {
    private val allowed = listOf("*.selykvpn.com", "selykconnection.net")

    private fun ok(input: String): KeyParser.Key {
        val result = KeyParser.parse(input, allowed)
        assertTrue("expected Ok for $input but got $result", result is KeyParser.Result.Ok)
        return (result as KeyParser.Result.Ok).key
    }

    @Test
    fun plainSubscriptionLink() {
        val key = ok("https://sub.selykvpn.com/abcDEF123456XYZ0")
        assertEquals("https://sub.selykvpn.com/abcDEF123456XYZ0", key.url)
        assertEquals("sub.selykvpn.com", key.host)
        assertEquals("/abcDEF123456XYZ0", key.path)
        assertEquals("abcDEF123456XYZ0", key.shortId)
    }

    @Test
    fun clientSuffixAndSlashAreRemoved() {
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok("https://sub.selykvpn.com/abcDEF123456/json").url)
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok("https://sub.selykvpn.com/abcDEF123456/").url)
        assertEquals("https://selykconnection.net/abcDEF123456", ok("http://selykconnection.net/abcDEF123456/v2ray-json").url)
    }

    @Test
    fun botImportLinks() {
        val encoded = "https://tgapp.selykvpn.xyz/import?url=https%3A%2F%2Fsub.selykvpn.com%2FabcDEF123456"
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok(encoded).url)
        val raw = "https://tgapp.selykvpn.xyz/import?url=https://sub.selykvpn.com/abcDEF123456"
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok(raw).url)
        val scheme = "selykvpn://import?url=https%3A%2F%2Fselykconnection.net%2FabcDEF123456"
        assertEquals("https://selykconnection.net/abcDEF123456", ok(scheme).url)
    }

    @Test
    fun linkInsideMessageText() {
        val text = "Ваш ключ:\nhttps://sub.selykvpn.com/abcDEF123456.\nПриятного пользования!"
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok(text).url)
        assertEquals("https://sub.selykvpn.com/abcDEF123456", ok("sub.selykvpn.com/abcDEF123456").url)
    }

    @Test
    fun foreignAndBrokenKeys() {
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse("https://sub.fgnconnection.com/abcDEF123456", allowed))
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse("https://selykvpn.com.evil.net/abcDEF123456", allowed))
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse("https://evilselykconnection.net/abcDEF123456", allowed))
        // Foreign VLESS server IP is rejected
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse("vless://uuid@1.2.3.4:443?security=reality#x", allowed))
        // Our VLESS server IP is accepted
        val vlessRes = KeyParser.parse("vless://uuid@144.31.226.239:443?security=reality#x", allowed)
        assertTrue(vlessRes is KeyParser.Result.Ok)
        assertEquals("vless://import", (vlessRes as KeyParser.Result.Ok).key.url)
        assertEquals(1, vlessRes.key.directConfigs?.size)
        // Foreign FreeAccess peerAddress is rejected
        val foreignFreeAccess = """
            {
              "type": "connection",
              "version": 1,
              "settings": {
                "serverName": "Foreign Free",
                "peerAddress": "foreign-host.com:56000"
              }
            }
        """.trimIndent()
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse(foreignFreeAccess, allowed))
        assertEquals(KeyParser.Result.NotALink, KeyParser.parse("просто текст", allowed))
        assertEquals(KeyParser.Result.NotALink, KeyParser.parse("https://sub.selykvpn.com/", allowed))
        assertEquals(KeyParser.Result.Empty, KeyParser.parse("   ", allowed))
    }

    @Test
    fun wildcardMatching() {
        assertTrue(KeyParser.hostAllowed("sub.selykvpn.com", allowed))
        assertTrue(KeyParser.hostAllowed("a.b.selykvpn.com", allowed))
        assertTrue(KeyParser.hostAllowed("SUB.SELYKVPN.COM.", allowed))
        assertFalse(KeyParser.hostAllowed("selykvpn.com", allowed))
        assertFalse(KeyParser.hostAllowed("sub.selykconnection.net", allowed))
        assertTrue(KeyParser.hostAllowed("selykconnection.net", allowed))
    }

    @Test
    fun freeAccessWrapAParsing() {
        val jsonKey = """
            {
              "type": "connection",
              "version": 1,
              "settings": {
                "serverName": "Свободный доступ 1",
                "useWrapA": true,
                "serverPassword": "test-secret-password",
                "deviceId": "device-12345",
                "vkLink": "https://vk.com/call/join/abcdef123456",
                "peerAddress": "media-stream.xamudhabibi.qzz.io:56000"
              }
            }
        """.trimIndent()
        val res = KeyParser.parse(jsonKey, allowed)
        assertTrue(res is KeyParser.Result.FreeAccess)
        val cfg = (res as KeyParser.Result.FreeAccess).config
        assertEquals(xyz.selykvpn.app.model.TunnelMode.SRTP_WRAP_A, cfg.mode)
        assertEquals("test-secret-password", cfg.serverPassword)
        assertEquals("device-12345", cfg.deviceId)
        val proxyJson = cfg.toProxyConfigJson()
        assertTrue(proxyJson.contains(""""use_wrap_a":true"""))
        assertTrue(proxyJson.contains(""""use_wrap":true"""))
        assertTrue(proxyJson.contains(""""wrap_a_password":"test-secret-password""""))
        assertTrue(proxyJson.contains(""""device_id":"device-12345""""))
    }

    @Test
    fun alternateXamudhabibiSubdomainsAcceptedEvenWithStaleAllowedList() {
        val staleRemoteAllowed = listOf(
            "*.selykvpn.xyz",
            "selykvpn.xyz",
            "panel.xamudhabibi.qzz.io",
        )
        val syncAlt = KeyParser.parse("https://sync.xamudhabibi.qzz.io/ScY5ZEgY9RjWa_Xk", staleRemoteAllowed)
        assertTrue("expected Ok for sync.xamudhabibi.qzz.io but got $syncAlt", syncAlt is KeyParser.Result.Ok)
        assertEquals("https://sync.xamudhabibi.qzz.io/ScY5ZEgY9RjWa_Xk", (syncAlt as KeyParser.Result.Ok).key.url)
        assertEquals("ScY5ZEgY9RjWa_Xk", syncAlt.key.shortId)

        val syncMain = KeyParser.parse("https://sync.selykvpn.xyz/ScY5ZEgY9RjWa_Xk", staleRemoteAllowed)
        assertTrue("expected Ok for sync.selykvpn.xyz but got $syncMain", syncMain is KeyParser.Result.Ok)
        assertEquals("https://sync.selykvpn.xyz/ScY5ZEgY9RjWa_Xk", (syncMain as KeyParser.Result.Ok).key.url)

        val wrappedAlt = KeyParser.parse(
            "https://tgapp.xamudhabibi.qzz.io/import?url=https%3A%2F%2Fsync.xamudhabibi.qzz.io%2FScY5ZEgY9RjWa_Xk",
            staleRemoteAllowed,
        )
        assertTrue(wrappedAlt is KeyParser.Result.Ok)
        assertEquals("https://sync.xamudhabibi.qzz.io/ScY5ZEgY9RjWa_Xk", (wrappedAlt as KeyParser.Result.Ok).key.url)
    }

    @Test
    fun testParseVlessAndEntries() {
        val uri = "vless://b8bd454d-c4e9-4ebb-9467-d012a1497acb@144.31.226.239:443?type=tcp&security=reality&pbk=1122334455667788&fp=chrome&sni=google.com&sid=1234#Финляндия"
        val res = KeyParser.parse(uri, allowed)
        assertTrue(res is KeyParser.Result.Ok)
        val key = (res as KeyParser.Result.Ok).key
        val entries = SubscriptionParser.entries(key.directConfigs!!)
        println("Entries: $entries")
        assertFalse(entries.none { it.kind != ServerKind.SEPARATOR })
    }

    @Test
    fun testParseVlessIpv6ForeignRejected() {
        val ipv6Foreign = "vless://b8bd454d-c4e9-4ebb-9467-d012a1497acb@[2001:db8::1]:8443?type=tcp&security=none#V6"
        assertEquals(KeyParser.Result.Foreign, KeyParser.parse(ipv6Foreign, allowed))
    }
}
