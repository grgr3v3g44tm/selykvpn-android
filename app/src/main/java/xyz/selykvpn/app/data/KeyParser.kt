package xyz.selykvpn.app.data

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * Turns whatever the user pasted, scanned or opened (a bare subscription link, a
 * https://tgapp.selykvpn.xyz/import?url=… link, selykvpn://import?url=…, or a message that contains
 * a link) into a normalized Селюк VPN subscription key.
 */
object KeyParser {

    /** Remnawave client-type suffixes: https://sub.example.com/<shortUuid>/<type>. */
    private val CLIENT_SUFFIXES = setOf("json", "v2ray-json", "singbox", "mihomo", "clash", "stash")
    private val IMPORT_HOSTS = setOf(
        "tgapp.selykvpn.xyz",
        "www.tgapp.selykvpn.xyz",
        "selykvpn.xyz",
        "tgapp.xamudhabibi.qzz.io",
        "www.tgapp.xamudhabibi.qzz.io",
    )

    private val URL_REGEX = Regex("""(?i)\b(?:https?|selykvpn)://[^\s"'<>«»]+""")
    private val BARE_HOST_REGEX = Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s"'<>«»]+""")
    private val URL_PARAM_REGEX = Regex("""[?&]url=([^&#]+)""")

    /** Known server IPs belonging exclusively to our VPN infrastructure. */
    val OUR_SERVER_IPS = setOf(
        "144.31.226.239",
        "144.31.79.239",
        "144.31.75.2",
        "144.31.75.80",
        "157.228.139.62",
    )

    /** Official domains of our service. */
    val OUR_DEFAULT_DOMAINS = listOf(
        "*.selykvpn.xyz",
        "selykvpn.xyz",
        "*.xamudhabibi.qzz.io",
        "xamudhabibi.qzz.io",
        "*.selykvpn.com",
        "selykvpn.com",
        "selykconnection.net",
    )

    fun mergedAllowedHosts(allowedHosts: List<String>): List<String> =
        (allowedHosts + OUR_DEFAULT_DOMAINS).distinct()

    fun isOurServerHostOrIp(hostOrIp: String, allowedHosts: List<String>): Boolean {
        val clean = hostOrIp.trim().lowercase().trimEnd('.').substringBefore(':').trim('[', ']')
        if (clean in OUR_SERVER_IPS) return true
        if (hostAllowed(clean, mergedAllowedHosts(allowedHosts))) return true
        return false
    }

    data class Key(
        val url: String,
        val host: String,
        val path: String,
        val directConfigs: List<JsonObject>? = null,
    ) {
        /** Remnawave short UUID: the last path segment. */
        val shortId: String get() = path.substringAfterLast('/')
    }

    sealed interface Result {
        data class Ok(val key: Key) : Result
        data class FreeAccess(val config: xyz.selykvpn.app.model.FreeAccessConfig) : Result
        data object Empty : Result
        data object NotALink : Result
        data object Foreign : Result
    }

    fun parse(input: String?, allowedHosts: List<String>): Result {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return Result.Empty

        // Check if it is a Free Access / VK TURN key or link (must point to our trusted gateways)
        xyz.selykvpn.app.model.FreeAccessConfig.parse(text)?.let { cfg ->
            val peerHost = cfg.peerAddress.substringBefore(':')
            return if (isOurServerHostOrIp(peerHost, allowedHosts)) {
                Result.FreeAccess(cfg)
            } else {
                Result.Foreign
            }
        }

        // Check if it is a direct vless:// key or base64 vless list (must point to our servers)
        if (text.contains("vless://", ignoreCase = true) || (!text.startsWith("http", ignoreCase = true) && text.length > 50)) {
            val vlessConfigs = SubscriptionParser.parseVlessList(text)
            if (vlessConfigs.isNotEmpty()) {
                val allOurs = vlessConfigs.all { cfg ->
                    val proxies = (cfg["outbounds"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
                        ?.filter { (it["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "vless" }
                    val first = proxies?.firstOrNull() ?: return@all false
                    val addr = SubscriptionParser.addressOf(first) ?: return@all false
                    isOurServerHostOrIp(addr, allowedHosts)
                }
                return if (allOurs) {
                    Result.Ok(
                        Key(
                            url = "vless://import",
                            host = "local",
                            path = "/vless",
                            directConfigs = vlessConfigs,
                        )
                    )
                } else {
                    Result.Foreign
                }
            }
        }

        var candidate = findUrl(text) ?: return Result.NotALink
        repeat(3) { candidate = unwrap(candidate) ?: return@repeat }
        val url = candidate.toHttpUrlOrNull() ?: return Result.NotALink
        val host = url.host.lowercase().trimEnd('.')
        if (host in IMPORT_HOSTS) return Result.NotALink
        if (!hostAllowed(host, mergedAllowedHosts(allowedHosts))) return Result.Foreign
        return normalize(url, host)?.let { Result.Ok(it) } ?: Result.NotALink
    }

    fun hostAllowed(host: String, patterns: List<String>): Boolean {
        val cleanPatterns = patterns.map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() && it != "*" }
        if (cleanPatterns.isEmpty()) return false
        val h = host.lowercase().trimEnd('.')
        return cleanPatterns.any { p ->
            when {
                p.startsWith("*.") -> {
                    val suffix = p.substring(1)
                    h.endsWith(suffix) && h.length > suffix.length
                }
                else -> h == p
            }
        }
    }

    private fun findUrl(text: String): String? {
        URL_REGEX.find(text)?.let { return it.value.trimEnd('.', ',', ')', ']', '!', '?', ';') }
        BARE_HOST_REGEX.find(text)?.let { return "https://" + it.value.trimEnd('.', ',', ')', ']', '!', '?', ';') }
        return null
    }

    /** Extracts the inner key from import wrappers, or returns null when [link] is not a wrapper. */
    private fun unwrap(link: String): String? {
        val lower = link.lowercase()
        val isWrapper = lower.startsWith("selykvpn://") ||
            IMPORT_HOSTS.any { lower.startsWith("https://$it/") || lower.startsWith("http://$it/") }
        if (!isWrapper) return null
        URL_PARAM_REGEX.find(link)?.let { match ->
            return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
        }
        // selykvpn://add/<url>
        val tail = link.substringAfter("://").substringAfter('/', "")
        return tail.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    }

    private fun normalize(url: HttpUrl, host: String): Key? {
        if (url.scheme != "https" && url.scheme != "http") return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.size > 1 && segments.last().lowercase() in CLIENT_SUFFIXES) segments.removeAt(segments.lastIndex)
        if (segments.isEmpty()) return null
        val builder = HttpUrl.Builder().scheme("https").host(host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) builder.port(url.port)
        segments.forEach { builder.addPathSegment(it) }
        val normalized = builder.build()
        return Key(url = normalized.toString(), host = host, path = normalized.encodedPath)
    }
}
