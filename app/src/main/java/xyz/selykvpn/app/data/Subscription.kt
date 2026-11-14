package xyz.selykvpn.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import okio.ByteString.Companion.decodeBase64

@Serializable
enum class ServerKind { SERVER, AUTO, SEPARATOR }

@Serializable
data class ServerEntry(
    /** Stable id across subscription updates: remarks plus occurrence number. */
    val key: String,
    val remarks: String,
    val flag: String? = null,
    val title: String,
    val kind: ServerKind,
    val summary: String? = null,
)

/** A server inside an auto-select config: its outbound tag and, when found, the same server in the list. */

@Serializable
data class SubscriptionInfo(
    val url: String,
    val title: String? = null,
    val announce: String? = null,
    val supportUrl: String? = null,
    val updateIntervalHours: Int? = null,
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
    val fetchedAt: Long,
    val servers: List<ServerEntry>,
) {
    val selectable: List<ServerEntry> get() = servers.filter { it.kind != ServerKind.SEPARATOR }
}

/** Parses the Remnawave Xray JSON subscription: an array of complete Xray configs with "remarks". */
object SubscriptionParser {

    private val PROXY_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http",
    )

    fun parseConfigs(body: String): List<JsonObject> {
        val root = runCatching { AppJson.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return items.mapNotNull { it as? JsonObject }.filter { it["outbounds"] is JsonArray }
    }

    /**
     * Parses standard v2ray / base64 subscription lists or individual vless:// links into Xray configs.
     */
    fun parseVlessList(body: String): List<JsonObject> {
        val text = body.trim()
        val decoded = if (!text.startsWith("vless://", ignoreCase = true) && !text.startsWith("[") && !text.startsWith("{")) {
            decodeFlexibleBase64Utf8(text) ?: text
        } else text

        return decoded.lines()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { parseVlessUri(it) }
    }

    private fun decodeFlexibleBase64Utf8(raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val standard = compact.replace('-', '+').replace('_', '/')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return (padded.decodeBase64() ?: compact.decodeBase64())?.utf8()
    }

    private fun decodePercentLiteral(raw: String): String {
        if ('%' !in raw) return raw
        val out = ByteArray(raw.length)
        var outLen = 0
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hi = raw[i + 1].digitToIntOrNull(16)
                val lo = raw[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out[outLen++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            for (b in bytes) out[outLen++] = b
            i++
        }
        return String(out, 0, outLen, Charsets.UTF_8)
    }

    fun parseVlessUri(uriString: String): JsonObject? {
        return runCatching {
            val withoutScheme = uriString.substringAfter("://")
            val mainAndFragment = withoutScheme.split('#', limit = 2)
            val main = mainAndFragment[0]
            val remarks = if (mainAndFragment.size > 1) {
                runCatching { decodePercentLiteral(mainAndFragment[1]) }.getOrDefault(mainAndFragment[1])
            } else "Server"

            val atSplit = main.split('@', limit = 2)
            if (atSplit.size < 2) return null
            val uuid = atSplit[0]

            val hostAndQuery = atSplit[1].split('?', limit = 2)
            val rawHostPort = hostAndQuery[0].trim()
            val (address, port) = if (rawHostPort.startsWith('[')) {
                val endBracket = rawHostPort.indexOf(']')
                if (endBracket <= 1) return null
                val addr = rawHostPort.substring(1, endBracket)
                val portStr = rawHostPort.substring(endBracket + 1).removePrefix(":")
                addr to (portStr.toIntOrNull() ?: 443)
            } else {
                val hostAndPort = rawHostPort.split(':', limit = 2)
                hostAndPort[0] to (hostAndPort.getOrNull(1)?.toIntOrNull() ?: 443)
            }

            val queryParams = mutableMapOf<String, String>()
            if (hostAndQuery.size > 1) {
                for (param in hostAndQuery[1].split('&')) {
                    val kv = param.split('=', limit = 2)
                    if (kv.isNotEmpty()) {
                        val k = kv[0]
                        val v = if (kv.size > 1) decodePercentLiteral(kv[1]) else ""
                        queryParams[k] = v
                    }
                }
            }

            val type = queryParams["type"] ?: "tcp"
            val security = queryParams["security"] ?: "none"
            val encryption = queryParams["encryption"] ?: "none"
            val sni = queryParams["sni"] ?: queryParams["serverName"] ?: address
            val flow = queryParams["flow"]

            val clientObj = buildJsonObject {
                put("id", uuid)
                put("encryption", encryption)
                if (!flow.isNullOrBlank()) put("flow", flow)
            }
            val vnextObj = buildJsonObject {
                put("address", address)
                put("port", port)
                put("users", buildJsonArray { add(clientObj) })
            }
            val settingsObj = buildJsonObject {
                put("vnext", buildJsonArray { add(vnextObj) })
            }

            val streamObj = buildJsonObject {
                put("network", type)
                put("security", security)
                if (security == "reality") {
                    val realityObj = buildJsonObject {
                        put("serverName", sni)
                        queryParams["fp"]?.let { put("fingerprint", it) }
                        queryParams["pbk"]?.let { put("publicKey", it) }
                        queryParams["sid"]?.let { put("shortId", it) }
                        queryParams["spx"]?.let { put("spiderX", it) }
                    }
                    put("realitySettings", realityObj)
                } else if (security == "tls") {
                    val tlsObj = buildJsonObject {
                        put("serverName", sni)
                        queryParams["fp"]?.let { put("fingerprint", it) }
                        queryParams["alpn"]?.let { alpn ->
                            put("alpn", buildJsonArray { alpn.split(',').forEach { add(it.trim()) } })
                        }
                    }
                    put("tlsSettings", tlsObj)
                }
                if (type == "ws") {
                    val wsObj = buildJsonObject {
                        put("path", queryParams["path"] ?: "/")
                        queryParams["host"]?.let { put("headers", buildJsonObject { put("Host", it) }) }
                    }
                    put("wsSettings", wsObj)
                } else if (type == "xhttp" || type == "splithttp") {
                    val xhttpObj = buildJsonObject {
                        put("path", queryParams["path"] ?: "/")
                        put("mode", queryParams["mode"] ?: "stream-one")
                        queryParams["extra"]?.let { extra ->
                            runCatching { AppJson.parseToJsonElement(extra) }.getOrNull()?.let { put("extra", it) }
                        }
                    }
                    put("xhttpSettings", xhttpObj)
                } else if (type == "grpc") {
                    val grpcObj = buildJsonObject {
                        put("serviceName", queryParams["serviceName"] ?: "")
                    }
                    put("grpcSettings", grpcObj)
                }
            }

            val proxyOutbound = buildJsonObject {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", settingsObj)
                put("streamSettings", streamObj)
            }
            val directOutbound = buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
            }
            val blockOutbound = buildJsonObject {
                put("tag", "block")
                put("protocol", "blackhole")
            }

            buildJsonObject {
                put("remarks", remarks)
                put("outbounds", buildJsonArray {
                    add(proxyOutbound)
                    add(directOutbound)
                    add(blockOutbound)
                })
            }
        }.getOrNull()
    }

    fun entries(configs: List<JsonObject>): List<ServerEntry> {
        val seen = HashMap<String, Int>()
        return configs.mapIndexed { index, config ->
            val remarks = (config["remarks"] as? JsonPrimitive)?.content?.trim().orEmpty()
                .ifEmpty { "Server ${index + 1}" }
            val occurrence = seen.merge(remarks, 1, Int::plus)!!
            val proxies = config["outbounds"]!!.jsonArray.mapNotNull { it as? JsonObject }
                .filter { it.string("protocol") in PROXY_PROTOCOLS }
            val first = proxies.firstOrNull()
            val balancers = (config["routing"] as? JsonObject)?.get("balancers") as? JsonArray
            val kind = when {
                first == null || addressOf(first) == "0.0.0.0" -> ServerKind.SEPARATOR
                proxies.size > 1 || !balancers.isNullOrEmpty() -> ServerKind.AUTO
                else -> ServerKind.SERVER
            }
            val (flag, title) = if (kind == ServerKind.SEPARATOR) null to separatorTitle(remarks) else splitFlag(remarks)
            ServerEntry(
                key = "$remarks#$occurrence",
                remarks = remarks,
                flag = flag,
                title = title,
                kind = kind,
                summary = first?.takeIf { kind == ServerKind.SERVER }?.let(::summaryOf),
            )
        }
    }

    fun addressOf(outbound: JsonObject): String? {
        val settings = outbound["settings"] as? JsonObject ?: return null
        (settings["vnext"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("address")?.let { return it }
        (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("address")?.let { return it }
        (settings["peers"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("endpoint")?.let { return it.substringBeforeLast(':') }
        return settings.string("address")
    }

    private fun summaryOf(outbound: JsonObject): String {
        val stream = outbound["streamSettings"] as? JsonObject
        // Always QUIC with TLS; "hysteria" is both its protocol and its transport.
        if (outbound.string("protocol") == "hysteria") return "HYSTERIA2 · QUIC"
        val protocol = outbound.string("protocol")?.uppercase()
        val network = when (val n = stream?.string("network")?.lowercase()) {
            null, "raw", "tcp" -> "TCP"
            "ws" -> "WS"
            "xhttp", "splithttp" -> "XHTTP"
            "grpc" -> "gRPC"
            "httpupgrade" -> "HTTPUpgrade"
            "kcp", "mkcp" -> "mKCP"
            else -> n.uppercase()
        }
        val security = when (stream?.string("security")?.lowercase()) {
            "tls" -> "TLS"
            "reality" -> "REALITY"
            else -> null
        }
        return listOfNotNull(protocol, network, security).joinToString(" · ")
    }

    /** "🇳🇱 Нидерланды ⚡" -> ("🇳🇱", "Нидерланды ⚡"). */
    fun splitFlag(remarks: String): Pair<String?, String> {
        val cps = remarks.codePoints().toArray()
        if (cps.size >= 2 && isRegionalIndicator(cps[0]) && isRegionalIndicator(cps[1])) {
            val flag = String(cps, 0, 2)
            val rest = String(cps, 2, cps.size - 2).trim()
            return flag to rest.ifEmpty { remarks }
        }
        return null to remarks
    }

    /** "⬇️ Внизу Домашние (Wi-Fi) ⬇️" -> "Домашние (Wi-Fi)". */
    fun separatorTitle(remarks: String): String {
        val cleaned = remarks.replace(SEPARATOR_DECOR, " ").replace(Regex("\\s+"), " ").trim()
        return cleaned.replace(SEPARATOR_PREFIX, "").trim().ifEmpty { remarks.trim() }
    }

    private val SEPARATOR_DECOR = Regex(
        "[\\x{2B07}\\x{2B06}\\x{2B05}\\x{27A1}\\x{2193}\\x{2191}\\x{2190}\\x{2192}\\x{FE0F}" +
            "\\x{1F447}\\x{1F446}\\x{1F53D}\\x{1F53C}]|[—–-]{2,}",
    )
    private val SEPARATOR_PREFIX = Regex("(?iu)^(внизу|ниже|below)\\s+")

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Remnawave header values may be prefixed with "base64:". */
    fun decodeHeader(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!v.startsWith("base64:", ignoreCase = true)) return v
        return v.substringAfter(':').trim().decodeBase64()?.utf8()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** "upload=1; download=2; total=3; expire=4" -> map. */
    fun parseUserInfo(value: String?): Map<String, Long> =
        value.orEmpty().split(';').mapNotNull { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val trimmed = v.trim()
            val num = trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()?.toLong()
            num?.let { k.trim().lowercase() to it }
        }.toMap()

    fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content
}
