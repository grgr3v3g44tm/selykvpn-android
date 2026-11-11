package xyz.selykvpn.app.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

@Serializable
enum class TunnelMode {
    SRTP,
    SRTP_WRAP_A,
    SRTP_WRAP_S,
    LEGACY
}

@Serializable
data class FreeAccessConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Свободный доступ",
    var mode: TunnelMode = TunnelMode.SRTP_WRAP_A,
    var stealthLink: String = "",
    var needsStealthLink: Boolean = false,
    var peerAddress: String = "media-stream.xamudhabibi.qzz.io:56000",
    var numConnections: Int = 8,
    var useUDP: Boolean = true,
    var useDTLS: Boolean = true,
    var useSrtp: Boolean = true,
    var useWrapA: Boolean = true,
    var wrapKeyHex: String = "",
    var serverPassword: String = "",
    var deviceId: String = "",
    var useSessionAuth: Boolean = false,
    var uplinkPaceKiB: Int = 0,
    var uplinkPaceBurstKiB: Int = 16,

    // WireGuard params
    var privateKey: String = "",
    var peerPublicKey: String = "",
    var presharedKey: String = "",
    var tunnelAddress: String = "10.77.77.2/24",
    var dnsServers: String = "1.1.1.1, 8.8.8.8",
    var mtu: Int = 1280
) {
    fun getParsedStealthLinks(): List<String> {
        return stealthLink.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("placeholder") }
    }

    val effectiveNumConnections: Int
        get() = numConnections.coerceIn(1, 32)

    fun toProxyConfigJson(): String {
        val links = getParsedStealthLinks()
        val primary = links.firstOrNull { it.contains("call/join/") }
            ?: links.firstOrNull()
            ?: stealthLink.trim()

        val stableDeviceId = if (deviceId.isNotBlank()) {
            deviceId
        } else {
            try {
                val app = xyz.selykvpn.app.SelykApp.instance
                val prefs = app.getSharedPreferences("vkturn_device_prefs", android.content.Context.MODE_PRIVATE)
                var hwid = prefs.getString("device_hwid", null)
                if (hwid.isNullOrBlank()) {
                    val androidId = android.provider.Settings.Secure.getString(
                        app.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                    hwid = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: UUID.randomUUID().toString()
                    prefs.edit().putString("device_hwid", hwid).apply()
                }
                hwid
            } catch (_: Exception) {
                id.replace("-", "").take(16)
            }
        }

        val json = buildJsonObject {
            put("vk_link", primary)
            putJsonArray("vk_cookie_links") {
                for (l in links) add(kotlinx.serialization.json.JsonPrimitive(l))
            }
            put("peer_addr", peerAddress)
            put("use_dtls", useDTLS)
            put("use_udp", useUDP)
            put("mtu", mtu.coerceIn(1200, 1280))
            put("num_conns", effectiveNumConnections)
            put("use_cookie_auth", useSessionAuth)
            if (uplinkPaceKiB > 0) {
                put("uplink_pace_kib", uplinkPaceKiB)
                put("uplink_pace_burst_kib", uplinkPaceBurstKiB)
            }
            put("force_legacy_captcha", false)
            put("memstats_fast_ticks", false)

            when (mode) {
                TunnelMode.SRTP -> {
                    put("use_srtp", true)
                    put("use_wrap", false)
                }
                TunnelMode.SRTP_WRAP_A -> {
                    put("use_srtp", true)
                    put("use_wrap", true)
                    put("use_wrap_a", true)
                    put("wrap_a_password", serverPassword)
                    put("device_id", stableDeviceId)
                    if (wrapKeyHex.isNotBlank()) {
                        put("wrap_key_hex", wrapKeyHex)
                    }
                }
                TunnelMode.SRTP_WRAP_S -> {
                    put("use_srtp", true)
                    put("use_wrap", true)
                    put("use_wrap_s", true)
                    put("client_id", stableDeviceId)
                    if (wrapKeyHex.isNotBlank()) {
                        put("wrap_key_hex", wrapKeyHex)
                    }
                }
                TunnelMode.LEGACY -> {
                    put("use_srtp", false)
                    put("use_wrap", false)
                }
            }
        }
        return json.toString()
    }

    fun toUAPIConfig(): String {
        if (mode == TunnelMode.SRTP_WRAP_A) {
            return ""
        }
        val sb = StringBuilder()
        sb.append("private_key=").append(keyToHex(privateKey)).append("\n")
        sb.append("replace_peers=true\n")
        sb.append("public_key=").append(keyToHex(peerPublicKey)).append("\n")
        sb.append("endpoint=").append(peerAddress).append("\n")
        sb.append("persistent_keepalive_interval=25\n")
        sb.append("allowed_ip=0.0.0.0/0\n")
        if (presharedKey.isNotBlank()) {
            sb.append("preshared_key=").append(keyToHex(presharedKey)).append("\n")
        }
        return sb.toString()
    }

    companion object {
        const val PLACEHOLDER_LINK = "selykvpn://stealth_placeholder"

        fun parse(input: String?): FreeAccessConfig? {
            val text = input?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.startsWith("vkturnproxy://") || text.startsWith("selykvpn://turn")) {
                return parseUri(text)
            }
            if (text.startsWith("{") && text.endsWith("}")) {
                return parseJson(text)
            }
            if (text.contains("[Interface]", ignoreCase = true)) {
                return parseWireGuardConf(text)
            }
            // Flexible base64
            val decoded = decodeBase64Flexible(text)
            if (decoded != null && decoded.isNotEmpty()) {
                val jsonStr = String(decoded, Charsets.UTF_8).trim()
                if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                    return parseJson(jsonStr)
                }
            }
            return null
        }

        private fun parseUri(uriString: String): FreeAccessConfig? {
            return try {
                val uri = android.net.Uri.parse(uriString)
                val data = uri.getQueryParameter("data") ?: return null
                val bytes = decodeBase64Flexible(data) ?: return null
                parseJson(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

        fun parseJson(jsonString: String): FreeAccessConfig? {
            return try {
                val root = com.google.gson.Gson().fromJson(jsonString, com.google.gson.JsonObject::class.java) ?: return null
                val settings = if (root.has("settings")) root.getAsJsonObject("settings") else root

                val name = settings.get("serverName")?.asString
                    ?: settings.get("name")?.asString
                    ?: "Свободный доступ"
                val vkLink = settings.get("vkLink")?.asString
                    ?: settings.get("vk_link")?.asString
                    ?: ""
                val needsStealthLink = vkLink.trim().contains("placeholder", ignoreCase = true) || vkLink.isBlank()

                val peerAddress = settings.get("peerAddress")?.asString
                    ?: settings.get("peer_addr")?.asString
                    ?: "media-stream.xamudhabibi.qzz.io:56000"
                val numConns = settings.get("numConnections")?.asInt
                    ?: settings.get("num_conns")?.asInt
                    ?: 8

                val useDTLS = settings.get("useDTLS")?.asBoolean ?: true
                val useUDP = settings.get("useUDP")?.asBoolean
                    ?: settings.get("use_udp")?.asBoolean
                    ?: true
                val useSrtp = settings.get("useSrtp")?.asBoolean ?: true
                val useWrapA = settings.get("useWrapA")?.asBoolean ?: false
                val useWrapS = settings.get("useWrapS")?.asBoolean ?: false

                val wrapKeyHex = settings.get("wrapKeyHex")?.asString
                    ?: settings.get("wrap_key_hex")?.asString
                    ?: ""
                val serverPassword = settings.get("serverPassword")?.asString
                    ?: settings.get("server_password")?.asString
                    ?: ""
                val deviceId = settings.get("deviceId")?.asString
                    ?: settings.get("device_id")?.asString
                    ?: ""

                val privateKey = settings.get("privateKey")?.asString
                    ?: settings.get("private_key")?.asString
                    ?: ""
                val peerPublicKey = settings.get("peerPublicKey")?.asString
                    ?: settings.get("peer_public_key")?.asString
                    ?: settings.get("publicKey")?.asString
                    ?: ""
                val presharedKey = settings.get("presharedKey")?.asString
                    ?: settings.get("preshared_key")?.asString
                    ?: ""
                val tunnelAddress = settings.get("tunnelAddress")?.asString
                    ?: settings.get("tunnel_address")?.asString
                    ?: settings.get("address")?.asString
                    ?: "10.77.77.2/24"
                val dnsServers = settings.get("dnsServers")?.asString
                    ?: settings.get("dns_servers")?.asString
                    ?: settings.get("dns")?.asString
                    ?: "1.1.1.1, 8.8.8.8"
                val mtu = (settings.get("mtu")?.asInt ?: 1280).coerceIn(1200, 1280)

                val mode = when {
                    useWrapA -> TunnelMode.SRTP_WRAP_A
                    useWrapS -> TunnelMode.SRTP_WRAP_S
                    useSrtp -> TunnelMode.SRTP
                    else -> TunnelMode.LEGACY
                }

                FreeAccessConfig(
                    name = name,
                    mode = mode,
                    stealthLink = vkLink,
                    needsStealthLink = needsStealthLink,
                    peerAddress = peerAddress,
                    numConnections = numConns,
                    useUDP = useUDP,
                    useDTLS = useDTLS,
                    useSrtp = useSrtp,
                    useWrapA = useWrapA || mode == TunnelMode.SRTP_WRAP_A,
                    wrapKeyHex = wrapKeyHex,
                    serverPassword = serverPassword,
                    deviceId = deviceId,
                    privateKey = privateKey,
                    peerPublicKey = peerPublicKey,
                    presharedKey = presharedKey,
                    tunnelAddress = tunnelAddress,
                    dnsServers = dnsServers,
                    mtu = mtu
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseWireGuardConf(conf: String): FreeAccessConfig? {
            var privateKey = ""
            var address = "10.77.77.2/24"
            var dns = "1.1.1.1, 8.8.8.8"
            var mtu = 1280
            var publicKey = ""
            var endpoint = "media-stream.xamudhabibi.qzz.io:56000"
            var presharedKey = ""

            for (rawLine in conf.lines()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith('[')) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()

                when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> address = value
                    "dns" -> dns = value
                    "mtu" -> mtu = value.toIntOrNull() ?: 1280
                    "publickey" -> publicKey = value
                    "endpoint" -> endpoint = value
                    "presharedkey" -> presharedKey = value
                }
            }

            if (privateKey.isBlank() && publicKey.isBlank()) return null
            return FreeAccessConfig(
                name = "Свободный доступ",
                mode = TunnelMode.SRTP,
                peerAddress = endpoint,
                privateKey = privateKey,
                peerPublicKey = publicKey,
                presharedKey = presharedKey,
                tunnelAddress = address,
                dnsServers = dns,
                mtu = mtu
            )
        }

        private fun decodeBase64Flexible(input: String): ByteArray? {
            val variants = intArrayOf(
                Base64.DEFAULT,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                Base64.URL_SAFE or Base64.NO_WRAP,
                Base64.NO_PADDING or Base64.NO_WRAP
            )
            for (flags in variants) {
                try {
                    val dec = Base64.decode(input, flags)
                    if (dec != null && dec.isNotEmpty()) return dec
                } catch (_: Exception) {}
            }
            return try {
                val norm = input.replace('-', '+').replace('_', '/')
                val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }

        fun keyToHex(key: String): String {
            val trimmed = key.trim()
            if (trimmed.length == 64 && trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
                return trimmed.lowercase()
            }
            return try {
                val bytes = Base64.decode(trimmed, Base64.DEFAULT)
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                trimmed
            }
        }
    }
}
