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
        assertEquals(KeyParser.Result.NotALink, KeyParser.parse("vless://uuid@1.2.3.4:443?security=reality#x", allowed))
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
}
