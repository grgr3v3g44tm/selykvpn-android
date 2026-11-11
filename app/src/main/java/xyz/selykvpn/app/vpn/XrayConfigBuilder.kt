package xyz.selykvpn.app.vpn

import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.RouteAction
import xyz.selykvpn.app.data.RouteRules
import xyz.selykvpn.app.data.RoutingSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom

/**
 * Turns a server config from the subscription into a config the app can run:
 * the panel's local socks/http inbounds are replaced by Xray's own TUN inbound, DNS from the
 * TUN is answered by Xray's DNS module, and traffic counters are enabled. Everything else —
 * outbounds, routing rules, balancers, observatory — is used exactly as the panel sent it.
 */
object XrayConfigBuilder {
    const val TUN_TAG = "tun-in"
    const val APP_PROXY_TAG = "app-in"
    const val DNS_OUT_TAG = "dns-out"
    const val SHARE_HTTP_TAG = "share-http"
    const val SHARE_SOCKS_TAG = "share-socks"
    const val SHARE_BLOCK_TAG = "share-block"
    const val USER_DIRECT_TAG = "user-direct"
    const val USER_BLOCK_TAG = "user-block"
    const val MTU = 1500

    /** Proxy for other devices on the same Wi-Fi / hotspot ("Раздача интернета"). */
    data class ShareProxy(val httpPort: Int, val socksPort: Int, val user: String? = null, val password: String? = null) {
        val auth: Boolean get() = !user.isNullOrEmpty() && !password.isNullOrEmpty()
    }

    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http")
    private val RULE_CONDITIONS = setOf("domain", "ip", "port", "sourcePort", "source", "protocol", "inboundTag", "user", "attrs")

    // Everything outside private networks (RFC 1918, CGNAT, link-local, IPv6 ULA and link-local),
    // including loopback: the share proxy only serves devices on the local network.
    private val NON_LOCAL_SOURCES = listOf(
        "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/3",
        "96.0.0.0/6", "100.0.0.0/10", "100.128.0.0/9", "101.0.0.0/8", "102.0.0.0/7", "104.0.0.0/5",
        "112.0.0.0/4", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/8", "169.0.0.0/9", "169.128.0.0/10",
        "169.192.0.0/11", "169.224.0.0/12", "169.240.0.0/13", "169.248.0.0/14", "169.252.0.0/15",
        "169.255.0.0/16", "170.0.0.0/7", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9",
        "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13",
        "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10",
        "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4", "224.0.0.0/3",
        "::/1", "8000::/2", "c000::/3", "e000::/4", "f000::/5", "f800::/6", "fe00::/9", "fec0::/10", "ff00::/8",
    )

    /** Local HTTP proxy for the app's own requests (the app is excluded from the VPN). */
    data class AppProxy(val port: Int, val user: String, val password: String) {
        companion object {
            fun random(): AppProxy {
                val rnd = SecureRandom()
                fun token() = buildString { repeat(24) { append("abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)]) } }
                return AppProxy(port = 20000 + rnd.nextInt(40000), user = token(), password = token())
            }
        }
    }

    private val defaultSniffing = buildJsonObject {
        put("enabled", true)
        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
        put("routeOnly", false)
    }

    fun build(
        raw: String,
        appProxy: AppProxy?,
        logLevel: String,
        share: ShareProxy? = null,
        routing: RoutingSettings? = null,
        fallback: String? = null,
    ): String {
        val source = AppJson.parseToJsonElement(raw).jsonObject
        val out = source.toMutableMap()
        out.remove("remarks")
        out.remove("remnawave")

        val templateInbounds = (source["inbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val sniffing = templateInbounds.firstNotNullOfOrNull { it["sniffing"] as? JsonObject } ?: defaultSniffing
        out["inbounds"] = buildJsonArray {
            addJsonObject {
                put("tag", TUN_TAG)
                put("protocol", "tun")
                put("port", 0)
                putJsonObject("settings") {
                    put("name", "tun0")
                    put("mtu", MTU)
                }
                put("sniffing", sniffing)
            }
            if (appProxy != null) {
                addJsonObject {
                    put("tag", APP_PROXY_TAG)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                    put("port", appProxy.port)
                    putJsonObject("settings") {
                        putJsonArray("accounts") {
                            addJsonObject {
                                put("user", appProxy.user)
                                put("pass", appProxy.password)
                            }
                        }
                        put("allowTransparent", false)
                    }
                }
            }
            if (share != null) {
                addJsonObject {
                    put("tag", SHARE_HTTP_TAG)
                    put("protocol", "http")
                    put("listen", "0.0.0.0")
                    put("port", share.httpPort)
                    putJsonObject("settings") {
                        if (share.auth) {
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", share.user)
                                    put("pass", share.password)
                                }
                            }
                        }
                        put("allowTransparent", false)
                    }
                    put("sniffing", sniffing)
                }
                addJsonObject {
                    put("tag", SHARE_SOCKS_TAG)
                    put("protocol", "socks")
                    put("listen", "0.0.0.0")
                    put("port", share.socksPort)
                    putJsonObject("settings") {
                        if (share.auth) {
                            put("auth", "password")
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", share.user)
                                    put("pass", share.password)
                                }
                            }
                        } else {
                            put("auth", "noauth")
                        }
                        put("udp", true)
                    }
                    put("sniffing", sniffing)
                }
            }
        }

        // DNS queries arriving from the TUN are resolved by Xray's DNS module (the panel's "dns" servers).
        val outbounds = (source["outbounds"] as? JsonArray).orEmpty().toMutableList()
        val templateOutbounds = outbounds.mapNotNull { it as? JsonObject }
        val existingDns = outbounds.mapNotNull { it as? JsonObject }.firstOrNull { it.string("protocol") == "dns" }
        val dnsTag = existingDns?.string("tag") ?: DNS_OUT_TAG.also {
            outbounds.add(buildJsonObject {
                put("tag", DNS_OUT_TAG)
                put("protocol", "dns")
            })
        }
        if (share != null) {
            outbounds.add(buildJsonObject {
                put("tag", SHARE_BLOCK_TAG)
                put("protocol", "blackhole")
            })
        }
        out["outbounds"] = JsonArray(outbounds)

        val routingSection = (source["routing"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val templateRules = (routingSection["rules"] as? JsonArray).orEmpty()
        val rules = templateRules.toMutableList()
        // The user's own rules go before the panel's ones.
        if (routing != null && routing.enabled) {
            rules.addAll(0, userRules(routing, templateOutbounds, templateRules, outbounds))
        }
        rules.add(0, buildJsonObject {
            put("type", "field")
            putJsonArray("inboundTag") { add(TUN_TAG) }
            put("port", "53")
            put("outboundTag", dnsTag)
        })
        if (share != null) {
            rules.add(0, buildJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(SHARE_HTTP_TAG); add(SHARE_SOCKS_TAG) }
                putJsonArray("source") { NON_LOCAL_SOURCES.forEach { add(it) } }
                put("outboundTag", SHARE_BLOCK_TAG)
            })
        }
        routingSection["rules"] = JsonArray(rules)
        // Auto-select sends traffic to the balancer's fallback until its first checks finish and while
        // none of its servers answers; start with the server that was best a moment ago.
        if (fallback != null && outbounds.any { (it as? JsonObject)?.string("tag") == fallback }) {
            (routingSection["balancers"] as? JsonArray)?.let { balancers ->
                routingSection["balancers"] = JsonArray(balancers.map { balancer ->
                    val obj = balancer as? JsonObject ?: return@map balancer
                    val selector = (obj["selector"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                    if (selector.any { fallback.startsWith(it) }) JsonObject(obj + ("fallbackTag" to JsonPrimitive(fallback))) else obj
                })
            }
        }
        out["routing"] = JsonObject(routingSection)
        out["outbounds"] = JsonArray(outbounds)

        // Servers are reached over IPv4; AAAA answers would make apps try IPv6 destinations
        // that the TUN accepts but the proxy cannot reach.
        val dns: MutableMap<String, JsonElement> = (source["dns"] as? JsonObject)?.toMutableMap() ?: mutableMapOf(
            "servers" to buildJsonArray { add("1.1.1.1"); add("8.8.8.8") },
        )
        dns["queryStrategy"] = JsonPrimitive("UseIPv4")
        // A plain (UDP) nameserver keeps one connection through whichever server auto-select picked
        // when it opened, and every DNS query of the phone (and of routing) goes through it for as
        // long as answers keep coming: a slow server keeps all DNS slow even after auto-select has
        // moved on. Over TCP every query is a new connection that the balancer sends to a live server.
        dns["servers"] = JsonArray((dns["servers"] as? JsonArray).orEmpty().map { overTcp(it) })
        // An expired answer is given right away while a fresh one is fetched in the background, so a
        // server switch doesn't stall every site for the length of a DNS query.
        if ("serveStale" !in dns) dns["serveStale"] = JsonPrimitive(true)
        out["dns"] = JsonObject(dns)

        out["stats"] = JsonObject(emptyMap())
        val policy = (source["policy"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val system = (policy["system"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        system["statsInboundUplink"] = JsonPrimitive(true)
        system["statsInboundDownlink"] = JsonPrimitive(true)
        policy["system"] = JsonObject(system)
        out["policy"] = JsonObject(policy)

        // Never write visited addresses to the system log.
        out["log"] = buildJsonObject {
            put("loglevel", logLevel)
            put("access", "none")
            put("dnsLog", false)
        }
        return JsonObject(out).toString()
    }

    /**
     * Rules from Settings → Routing: blocked first, then direct (with the LAN and .ru presets),
     * then through VPN. "Through VPN" uses the same route as the panel's catch-all rule, so it also
     * works for auto-select configs with balancers.
     */
    private fun userRules(
        routing: RoutingSettings,
        templateOutbounds: List<JsonObject>,
        templateRules: List<JsonElement>,
        outbounds: MutableList<JsonElement>,
    ): List<JsonObject> {
        fun tagOf(protocol: String, fallbackTag: String): String =
            templateOutbounds.firstOrNull { it.string("protocol") == protocol }?.string("tag")
                ?: fallbackTag.also { tag ->
                    if (outbounds.none { (it as? JsonObject)?.string("tag") == tag }) {
                        outbounds.add(buildJsonObject {
                            put("tag", tag)
                            put("protocol", protocol)
                        })
                    }
                }

        val catchAll = templateRules.mapNotNull { it as? JsonObject }
            .lastOrNull { rule -> rule.keys.none { it in RULE_CONDITIONS } && (rule["balancerTag"] != null || rule["outboundTag"] != null) }
        val proxyTarget: Pair<String, String> = when {
            catchAll?.get("balancerTag") != null -> "balancerTag" to catchAll.string("balancerTag")!!
            catchAll?.get("outboundTag") != null -> "outboundTag" to catchAll.string("outboundTag")!!
            else -> "outboundTag" to (
                templateOutbounds.firstOrNull { it.string("protocol") in PROXY_PROTOCOLS }?.string("tag")
                    ?: templateOutbounds.firstOrNull()?.string("tag") ?: "proxy"
                )
        }

        val result = mutableListOf<JsonObject>()
        for (action in listOf(RouteAction.BLOCK, RouteAction.DIRECT, RouteAction.PROXY)) {
            val entries = buildList {
                addAll(routing.rules(action))
                if (action == RouteAction.DIRECT && routing.lanDirect) addAll(RouteRules.LAN_RANGES)
                if (action == RouteAction.DIRECT && routing.ruDirect) addAll(RouteRules.RU_DOMAINS)
            }.mapNotNull { RouteRules.toXray(it) }.distinct()
            if (entries.isEmpty()) continue
            val target = when (action) {
                RouteAction.BLOCK -> "outboundTag" to tagOf("blackhole", USER_BLOCK_TAG)
                RouteAction.DIRECT -> "outboundTag" to tagOf("freedom", USER_DIRECT_TAG)
                RouteAction.PROXY -> proxyTarget
            }
            val (ips, domains) = entries.partition { RouteRules.isIpOrCidr(it) }
            if (domains.isNotEmpty()) {
                result += buildJsonObject {
                    put("type", "field")
                    putJsonArray("domain") { domains.forEach { add(it) } }
                    put(target.first, target.second)
                }
            }
            if (ips.isNotEmpty()) {
                result += buildJsonObject {
                    put("type", "field")
                    putJsonArray("ip") { ips.forEach { add(it) } }
                    put(target.first, target.second)
                }
            }
        }
        return result
    }

    /** "9.9.9.9" (DNS over UDP) becomes "tcp://9.9.9.9:53"; DoH, local and other servers stay as they are. */
    private fun overTcp(server: JsonElement): JsonElement = when (server) {
        is JsonPrimitive -> tcpAddress(server.content, 53)?.let(::JsonPrimitive) ?: server
        is JsonObject -> {
            val port = (server["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 53
            val tcp = server.string("address")?.let { tcpAddress(it, port) }
            if (tcp == null) server else JsonObject(server.filterKeys { it != "port" } + ("address" to JsonPrimitive(tcp)))
        }
        else -> server
    }

    private fun tcpAddress(address: String, port: Int): String? = when {
        '/' in address || !RouteRules.isIpOrCidr(address) -> null
        ':' in address -> "tcp://[$address]:$port"
        else -> "tcp://$address:$port"
    }

    /** Protocol of every outbound of a built config, by tag (the connection log tells VPN from direct). */
    fun outboundProtocols(config: String): Map<String, String> =
        (AppJson.parseToJsonElement(config).jsonObject["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { outbound -> outbound.string("tag")?.let { it to outbound.string("protocol").orEmpty() } }
            .toMap()

    /** Traffic counters of everything that goes through the VPN: the device itself and shared devices. */
    fun counters(direction: String) = listOf(TUN_TAG, SHARE_HTTP_TAG, SHARE_SOCKS_TAG)
        .map { "inbound>>>$it>>>traffic>>>$direction" }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content
}
