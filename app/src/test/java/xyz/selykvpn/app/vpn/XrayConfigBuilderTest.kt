package xyz.selykvpn.app.vpn

import xyz.selykvpn.app.data.AppJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {
    private val raw = """
        {"remarks":"x","inbounds":[{"tag":"socks","protocol":"socks","port":10808,"sniffing":{"enabled":true}}],
         "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
         "routing":{"rules":[{"type":"field","domain":["keyword:yandex"],"outboundTag":"direct"}]},
         "dns":{"servers":["77.88.8.8"],"queryStrategy":"UseIP"}}
    """

    @Test
    fun tunInboundDnsHijackAndNoAccessLog() {
        val cfg = AppJson.parseToJsonElement(XrayConfigBuilder.build(raw, null, "error")).jsonObject
        val inbounds = cfg["inbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("tun-in"), inbounds)
        val firstRule = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject
        assertEquals("dns-out", firstRule["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("UseIPv4", cfg["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)
        assertEquals("none", cfg["log"]!!.jsonObject["access"]!!.jsonPrimitive.content)
        assertFalse(cfg.containsKey("remarks"))
    }

    @Test
    fun shareProxyServesOnlyLocalNetwork() {
        val cfg = AppJson.parseToJsonElement(
            XrayConfigBuilder.build(raw, null, "error", XrayConfigBuilder.ShareProxy(8080, 1080)),
        ).jsonObject
        val inbounds = cfg["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tun-in", "share-http", "share-socks"), inbounds.map { it["tag"]!!.jsonPrimitive.content })
        assertTrue(inbounds.drop(1).all { it["listen"]!!.jsonPrimitive.content == "0.0.0.0" })

        val block = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject
        assertEquals("share-block", block["outboundTag"]!!.jsonPrimitive.content)
        val sources = block["source"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("loopback (127.0.0.0/8 is inside 112.0.0.0/4) must be blocked", "112.0.0.0/4" in sources && "::/1" in sources)
        assertFalse(sources.any { it.startsWith("10.") || it.startsWith("192.168.") })
        val outbounds = cfg["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("share-block" in outbounds)
    }

    @Test
    fun userRulesGoBeforeServerRulesAndUseTheBalancer() {
        val auto = """
            {"outbounds":[{"tag":"proxy-1","protocol":"vless"},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
             "routing":{"rules":[{"type":"field","protocol":["bittorrent"],"outboundTag":"direct"},
                                 {"type":"field","network":"tcp,udp","balancerTag":"Super_Balancer"}],
                        "balancers":[{"tag":"Super_Balancer","selector":["proxy"]}]}}
        """
        val routingWithDot = xyz.selykvpn.app.data.RoutingSettings(direct = setOf("domain:.com", "keyword:yandex"))
        val withDot = AppJson.parseToJsonElement(XrayConfigBuilder.build(auto, null, "error", null, routingWithDot)).jsonObject
        val directRule = withDot["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            .first { it["domain"] != null && it["outboundTag"]?.jsonPrimitive?.content == "direct" && "keyword:yandex" in it["domain"].toString() }
        assertEquals(listOf("domain:com", "keyword:yandex"), directRule["domain"]!!.jsonArray.map { it.jsonPrimitive.content })

        val routing = xyz.selykvpn.app.data.RoutingSettings(
            enabled = true, lanDirect = true, ruDirect = false,
            direct = setOf("domain:sber.ru"), proxy = setOf("domain:yandex.ru"), block = setOf("domain:ads.example", "1.2.3.4"),
        )
        val cfg = AppJson.parseToJsonElement(XrayConfigBuilder.build(auto, null, "error", null, routing)).jsonObject
        val rules = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // dns hijack, block (domain, ip), direct (domain, LAN ip), proxy, then the panel's rules
        assertEquals("dns-out", rules[0]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("block", rules[1]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("block", rules[2]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("direct", rules[3]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("direct", rules[4]["outboundTag"]!!.jsonPrimitive.content)
        assertTrue(rules[4]["ip"]!!.jsonArray.any { it.jsonPrimitive.content == "192.168.0.0/16" })
        assertEquals("Super_Balancer", rules[5]["balancerTag"]!!.jsonPrimitive.content)
        assertEquals("bittorrent", rules[6]["protocol"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun fallbackIsTheServerThatWasBestAMomentAgo() {
        val auto = """
            {"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"proxy-2","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
             "routing":{"rules":[{"type":"field","network":"tcp,udp","balancerTag":"Super_Balancer"}],
                        "balancers":[{"tag":"Super_Balancer","selector":["proxy"],"fallbackTag":"proxy"},
                                     {"tag":"Other","selector":["eu-"]}]}}
        """
        fun balancers(fallback: String?) = AppJson.parseToJsonElement(XrayConfigBuilder.build(auto, null, "error", null, null, fallback))
            .jsonObject["routing"]!!.jsonObject["balancers"]!!.jsonArray.map { it.jsonObject }
        val patched = balancers("proxy-2")
        assertEquals("proxy-2", patched[0]["fallbackTag"]!!.jsonPrimitive.content)
        // Not one of that balancer's servers.
        assertFalse(patched[1].containsKey("fallbackTag"))
        assertEquals("proxy", balancers("gone")[0]["fallbackTag"]!!.jsonPrimitive.content)
        assertEquals("proxy", balancers(null)[0]["fallbackTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun dnsGoesOverTcpSoEachQueryPicksALiveServer() {
        val template = """
            {"outbounds":[{"tag":"proxy","protocol":"vless"}],
             "dns":{"servers":["9.9.9.9","2606:4700:4700::1111",{"address":"77.88.8.8","port":5353,"domains":["domain:ru"]},
                               "localhost","https://dns.google/dns-query","tcp://1.1.1.1"]}}
        """
        val dns = AppJson.parseToJsonElement(XrayConfigBuilder.build(template, null, "error")).jsonObject["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray
        assertEquals("tcp://9.9.9.9:53", servers[0].jsonPrimitive.content)
        assertEquals("tcp://[2606:4700:4700::1111]:53", servers[1].jsonPrimitive.content)
        val withDomains = servers[2].jsonObject
        assertEquals("tcp://77.88.8.8:5353", withDomains["address"]!!.jsonPrimitive.content)
        assertFalse(withDomains.containsKey("port"))
        assertEquals("domain:ru", withDomains["domains"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(listOf("localhost", "https://dns.google/dns-query", "tcp://1.1.1.1"), servers.drop(3).map { it.jsonPrimitive.content })
        assertEquals("true", dns["serveStale"]!!.jsonPrimitive.content)
        assertEquals("UseIPv4", dns["queryStrategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun shareProxyWithPassword() {
        val cfg = AppJson.parseToJsonElement(
            XrayConfigBuilder.build(raw, null, "error", XrayConfigBuilder.ShareProxy(18080, 11080, "selyk_1", "secret")),
        ).jsonObject
        val socks = cfg["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "share-socks" }
        assertEquals(11080, socks["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("password", socks["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        val account = socks["settings"]!!.jsonObject["accounts"]!!.jsonArray[0].jsonObject
        assertEquals("selyk_1", account["user"]!!.jsonPrimitive.content)
    }
}
