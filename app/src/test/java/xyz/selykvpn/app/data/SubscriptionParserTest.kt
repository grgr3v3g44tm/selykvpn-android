package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionParserTest {

    private fun config(remarks: String, vararg addresses: String, balancer: Boolean = false): String {
        val outbounds = addresses.mapIndexed { i, address ->
            """{"tag":"proxy${if (i == 0) "" else "-$i"}","protocol":"vless","settings":{"vnext":[{"address":"$address","port":443,"users":[{"id":"x"}]}]},
               "streamSettings":{"network":"xhttp","security":"reality"}}"""
        } + """{"tag":"direct","protocol":"freedom"}"""
        val routing = if (balancer) ""","routing":{"balancers":[{"tag":"b","selector":["proxy"]}]}""" else ""
        return """{"remarks":"$remarks","outbounds":[${outbounds.joinToString(",")}]$routing}"""
    }

    @Test
    fun entriesFromRemnawaveJson() {
        val body = "[" + listOf(
            config("⬇️ Автовыбор ⬇️", "0.0.0.0"),
            config("🇪🇺 Автовыбор Wi-Fi 🎲", "a.net", "b.net", balancer = true),
            config("⬇️ Внизу Домашние (Wi-Fi) ⬇️", "0.0.0.0"),
            config("🇳🇱 Нидерланды ⚡", "nl.net"),
            config("🇳🇱 Нидерланды ⚡", "nl2.net"),
            config("Без флага", "x.net"),
        ).joinToString(",") + "]"
        val entries = SubscriptionParser.entries(SubscriptionParser.parseConfigs(body))

        assertEquals(listOf(ServerKind.SEPARATOR, ServerKind.AUTO, ServerKind.SEPARATOR, ServerKind.SERVER, ServerKind.SERVER, ServerKind.SERVER), entries.map { it.kind })
        assertEquals("Автовыбор", entries[0].title)
        assertEquals("Домашние (Wi-Fi)", entries[2].title)
        assertEquals("🇪🇺", entries[1].flag)
        assertEquals("Автовыбор Wi-Fi 🎲", entries[1].title)
        assertEquals("VLESS · XHTTP · REALITY", entries[3].summary)
        // Duplicate names get distinct, stable keys.
        assertEquals("🇳🇱 Нидерланды ⚡#1", entries[3].key)
        assertEquals("🇳🇱 Нидерланды ⚡#2", entries[4].key)
        assertNull(entries[5].flag)
        assertEquals("Без флага", entries[5].title)
    }

    @Test
    fun hysteriaServer() {
        // The shape Remnawave gives a Hysteria 2 host in the Xray JSON subscription.
        val body = """[{"remarks":"🇩🇪 Германия Hysteria","outbounds":[
            {"tag":"proxy","protocol":"hysteria","settings":{"address":"de.net","port":443,"version":2},
             "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"x"},"security":"tls",
               "tlsSettings":{"serverName":"de.net","alpn":["h3"]},
               "finalmask":{"udp":[{"type":"salamander","settings":{"password":"y"}}]}}},
            {"tag":"direct","protocol":"freedom"}]}]"""
        val entry = SubscriptionParser.entries(SubscriptionParser.parseConfigs(body)).single()
        assertEquals(ServerKind.SERVER, entry.kind)
        assertEquals("Германия Hysteria", entry.title)
        assertEquals("HYSTERIA2 · QUIC", entry.summary)
    }

    @Test
    fun headers() {
        assertEquals("SelykVPN | Подписка", SubscriptionParser.decodeHeader("base64:U2VseWtWUE4gfCDQn9C+0LTQv9C40YHQutCw"))
        assertEquals("plain", SubscriptionParser.decodeHeader("plain"))
        assertNull(SubscriptionParser.decodeHeader(""))
        assertEquals(
            mapOf("upload" to 1L, "download" to 2L, "total" to 0L, "expire" to 1790000000L),
            SubscriptionParser.parseUserInfo("upload=1; download=2; total=0; expire=1790000000"),
        )
    }

    @Test
    fun vlessIpv6AndPlusInQueryAndUnpaddedBase64() {
        val vless = "vless://uuid-1@[2001:db8::42]:8443?type=tcp&security=reality&pbk=abc+def/123=&sni=example.com#EU%20V6"
        val configs = SubscriptionParser.parseVlessList(vless)
        assertEquals(1, configs.size)
        val obj = configs[0]
        assertEquals("EU V6", obj["remarks"]!!.jsonPrimitive.content)
        val vnext = obj["outbounds"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject
        assertEquals("2001:db8::42", vnext["address"]!!.jsonPrimitive.content)
        assertEquals(8443, vnext["port"]!!.jsonPrimitive.content.toInt())
        val pbk = obj["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content
        assertEquals("abc+def/123=", pbk)

        // Unpadded URL-safe base64 subscription payload
        val unpadded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(vless.toByteArray())
        val decodedConfigs = SubscriptionParser.parseVlessList(unpadded)
        assertEquals(1, decodedConfigs.size)
    }
}
