package xyz.selykvpn.app.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLDecoder
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
enum class TunnelMode {
    SRTP,
    SRTP_WRAP,
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
    var useUDP: Boolean = false,
    var useDTLS: Boolean = true,
    var useSrtp: Boolean = true,
    var useWrap: Boolean = false,
    var useWrapA: Boolean = true,
    var wrapKeyHex: String = "",
    var serverPassword: String = "",
    var deviceId: String = "",
    var wrapSProfile: String = "auto",
    var useSessionAuth: Boolean = false,
    var uplinkPaceKiB: Int = 0,
    var uplinkPaceBurstKiB: Int = 16,
    var forceLegacyCaptcha: Boolean = false,
    var memstatsFastTicks: Boolean = false,

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
            .filter { it.isNotEmpty() && !it.contains("placeholder", ignoreCase = true) }
    }

    fun hasRealVKCallLink(): Boolean {
        if (stealthLink.isBlank()) return false
        return stealthLink.lines().any { line ->
            val trimmed = line.trim()
            trimmed.contains("call/join/") && !trimmed.contains("placeholder", ignoreCase = true)
        }
    }

    fun hasValidStealthLink(): Boolean = hasRealVKCallLink()

    fun hasGatewayCredentials(): Boolean = when (mode) {
        TunnelMode.SRTP_WRAP_A -> serverPassword.isNotBlank()
        else -> privateKey.isNotBlank() && peerPublicKey.isNotBlank()
    }

    fun isReadyForTunnel(): Boolean = hasRealVKCallLink() && hasGatewayCredentials()

    fun clearGatewayCredentials(): FreeAccessConfig = copy(
        serverPassword = "",
        wrapKeyHex = "",
        privateKey = "",
        peerPublicKey = "",
        presharedKey = "",
        needsStealthLink = true,
    )

    fun withCallLink(newLink: String): FreeAccessConfig {
        val clean = newLink.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") } ?: newLink.trim()
        return copy(stealthLink = clean, needsStealthLink = false, useSessionAuth = false)
    }

    /**
     * Merges credentials received from the subscription backend while keeping the user's
     * locally configured VK call link and VK account auth toggle.
     */
    fun mergeFromRemote(remote: FreeAccessConfig): FreeAccessConfig {
        val remoteHasCall = remote.hasRealVKCallLink()
        val keepLocalCall = hasRealVKCallLink()
        val keepLocalPassword = remote.serverPassword.isBlank() && serverPassword.isNotBlank()
        val effectiveStealth = when {
            remoteHasCall -> remote.stealthLink
            keepLocalCall -> stealthLink
            else -> remote.stealthLink
        }
        val needsCall = !(remoteHasCall || keepLocalCall)
        return remote.copy(
            id = id,
            name = remote.name.ifBlank { name },
            stealthLink = effectiveStealth,
            needsStealthLink = needsCall,
            serverPassword = if (keepLocalPassword) serverPassword else remote.serverPassword,
            privateKey = remote.privateKey.ifBlank { privateKey },
            peerPublicKey = remote.peerPublicKey.ifBlank { peerPublicKey },
            presharedKey = remote.presharedKey.ifBlank { presharedKey },
            tunnelAddress = remote.tunnelAddress.ifBlank { tunnelAddress },
            deviceId = deviceId.ifBlank { remote.deviceId },
            useSessionAuth = useSessionAuth,
        )
    }

    fun cookieConnCap(callLinksCount: Int): Int {
        return (callLinksCount * 20).coerceIn(2, 50)
    }

    val effectiveNumConnections: Int
        get() {
            if (!useSessionAuth) return numConnections.coerceIn(1, 60)
            val links = getParsedStealthLinks()
            val cap = cookieConnCap(links.size.coerceAtLeast(1))
            return minOf(numConnections.coerceIn(1, 60), cap)
        }

    fun toProxyConfigJson(): String {
        val links = getParsedStealthLinks()
        val primary = links.firstOrNull { it.contains("call/join/") }
            ?: links.firstOrNull()
            ?: stealthLink.trim()

        val stableDeviceId = deviceId.ifBlank {
            try {
                resolveStableDeviceId(xyz.selykvpn.app.SelykApp.instance)
            } catch (_: Throwable) {
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
            put("force_legacy_captcha", forceLegacyCaptcha)
            put("memstats_fast_ticks", memstatsFastTicks)

            when (mode) {
                TunnelMode.SRTP -> {
                    put("use_srtp", true)
                    put("use_wrap", false)
                }
                TunnelMode.SRTP_WRAP -> {
                    put("use_srtp", true)
                    put("use_wrap", true)
                    put("wrap_key_hex", wrapKeyHex)
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
                    put("wrap_key_hex", wrapKeyHex)
                    put("obf_profile", wrapSProfile)
                    put("client_id", stableDeviceId)
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
                val data = runCatching {
                    android.net.Uri.parse(uriString).getQueryParameter("data")
                }.getOrNull() ?: extractQueryDataFallback(uriString)
                    ?: return null
                val bytes = decodeBase64Flexible(data) ?: return null
                parseJson(String(bytes, Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }

        private fun extractQueryDataFallback(uriString: String): String? {
            val query = uriString.substringAfter('?', "")
            if (query.isEmpty()) return null
            for (part in query.split('&')) {
                val idx = part.indexOf('=')
                if (idx > 0 && part.substring(0, idx) == "data") {
                    val encoded = part.substring(idx + 1).substringBefore('#')
                    return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                }
            }
            return null
        }

        private fun jsonString(obj: com.google.gson.JsonObject, vararg keys: String): String? {
            for (k in keys) {
                val el = obj.get(k) ?: continue
                if (!el.isJsonNull && el.isJsonPrimitive) return el.asString
            }
            return null
        }

        private fun jsonBoolean(obj: com.google.gson.JsonObject, vararg keys: String): Boolean? {
            for (k in keys) {
                val el = obj.get(k) ?: continue
                if (!el.isJsonNull && el.isJsonPrimitive) {
                    val prim = el.asJsonPrimitive
                    if (prim.isBoolean) return prim.asBoolean
                    if (prim.isNumber) return prim.asInt != 0
                    if (prim.isString) {
                        return when (prim.asString.lowercase()) {
                            "true", "1" -> true
                            "false", "0" -> false
                            else -> null
                        }
                    }
                }
            }
            return null
        }

        private fun jsonInt(obj: com.google.gson.JsonObject, vararg keys: String): Int? {
            for (k in keys) {
                val el = obj.get(k) ?: continue
                if (!el.isJsonNull && el.isJsonPrimitive) {
                    runCatching { return el.asInt }
                }
            }
            return null
        }

        fun parseJson(jsonString: String): FreeAccessConfig? {
            return try {
                val root = com.google.gson.Gson().fromJson(jsonString, com.google.gson.JsonObject::class.java) ?: return null
                val settings = if (root.has("settings") && root.get("settings").isJsonObject) {
                    root.getAsJsonObject("settings")
                } else {
                    root
                }

                val name = jsonString(settings, "serverName", "name") ?: "Свободный доступ"
                val vkLink = jsonString(settings, "vkLink", "vk_link") ?: ""
                val needsStealthLink = vkLink.trim().contains("placeholder", ignoreCase = true) ||
                    !vkLink.contains("call/join/")

                val peerAddress = jsonString(settings, "peerAddress", "peer_addr") ?: "media-stream.xamudhabibi.qzz.io:56000"
                val numConns = jsonInt(settings, "numConnections", "num_conns") ?: 8

                val useDTLS = jsonBoolean(settings, "useDTLS", "use_dtls") ?: true
                val useUDP = jsonBoolean(settings, "useUDP", "use_udp") ?: false
                val useSrtp = jsonBoolean(settings, "useSrtp", "use_srtp") ?: true
                val useWrap = jsonBoolean(settings, "useWrap", "use_wrap") ?: false
                val useWrapAFlag = jsonBoolean(settings, "useWrapA", "use_wrap_a") ?: false
                val useWrapS = jsonBoolean(settings, "useWrapS", "use_wrap_s") ?: false

                val wrapKeyHex = jsonString(settings, "wrapKeyHex", "wrap_key_hex") ?: ""
                val serverPassword = jsonString(
                    settings,
                    "serverPassword",
                    "server_password",
                    "wrapAPassword",
                    "wrap_a_password",
                ) ?: ""
                val useWrapA = useWrapAFlag || (serverPassword.isNotBlank() && !useWrapS)
                val deviceId = jsonString(settings, "deviceId", "device_id", "client_id") ?: ""
                val wrapSProfile = jsonString(settings, "wrapSProfile", "wrap_s_profile", "obf_profile") ?: "auto"
                val useSessionAuth = jsonBoolean(settings, "useSessionAuth", "use_session_auth", "useCookieAuth", "use_cookie_auth") ?: false
                val uplinkPaceKiB = jsonInt(settings, "uplinkPaceKiB", "uplink_pace_kib") ?: 0
                val uplinkPaceBurstKiB = jsonInt(settings, "uplinkPaceBurstKiB", "uplink_pace_burst_kib") ?: 16
                val forceLegacyCaptcha = jsonBoolean(settings, "forceLegacyCaptcha", "force_legacy_captcha") ?: false
                val memstatsFastTicks = jsonBoolean(settings, "memstatsFastTicks", "memstats_fast_ticks") ?: false

                val privateKey = jsonString(settings, "privateKey", "private_key") ?: ""
                val peerPublicKey = jsonString(settings, "peerPublicKey", "peer_public_key", "publicKey") ?: ""
                val presharedKey = jsonString(settings, "presharedKey", "preshared_key") ?: ""
                val tunnelAddress = jsonString(settings, "tunnelAddress", "tunnel_address", "address") ?: "10.77.77.2/24"
                val dnsServers = jsonString(settings, "dnsServers", "dns_servers", "dns") ?: "1.1.1.1, 8.8.8.8"
                val mtu = (jsonInt(settings, "mtu") ?: 1280).coerceIn(1200, 1280)

                val mode = when {
                    useWrapA -> TunnelMode.SRTP_WRAP_A
                    useWrapS -> TunnelMode.SRTP_WRAP_S
                    useWrap -> TunnelMode.SRTP_WRAP
                    useSrtp -> TunnelMode.SRTP
                    else -> TunnelMode.LEGACY
                }

                FreeAccessConfig(
                    name = name,
                    mode = mode,
                    stealthLink = if (vkLink.contains("placeholder", ignoreCase = true)) "" else vkLink,
                    needsStealthLink = needsStealthLink,
                    peerAddress = peerAddress,
                    numConnections = numConns,
                    useUDP = useUDP,
                    useDTLS = useDTLS,
                    useSrtp = useSrtp,
                    useWrap = useWrap || mode == TunnelMode.SRTP_WRAP || mode == TunnelMode.SRTP_WRAP_A || mode == TunnelMode.SRTP_WRAP_S,
                    useWrapA = useWrapA,
                    wrapKeyHex = wrapKeyHex,
                    serverPassword = serverPassword,
                    deviceId = deviceId,
                    wrapSProfile = wrapSProfile,
                    useSessionAuth = useSessionAuth,
                    uplinkPaceKiB = uplinkPaceKiB,
                    uplinkPaceBurstKiB = uplinkPaceBurstKiB,
                    forceLegacyCaptcha = forceLegacyCaptcha,
                    memstatsFastTicks = memstatsFastTicks,
                    privateKey = privateKey,
                    peerPublicKey = peerPublicKey,
                    presharedKey = presharedKey,
                    tunnelAddress = tunnelAddress,
                    dnsServers = dnsServers,
                    mtu = mtu
                )
            } catch (_: Throwable) {
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

        @OptIn(ExperimentalEncodingApi::class)
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
                } catch (_: Throwable) {}
            }
            val norm = input.replace('-', '+').replace('_', '/')
            val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
            return runCatching {
                kotlin.io.encoding.Base64.decode(padded)
            }.getOrNull()
        }

        fun keyToHex(key: String): String {
            val trimmed = key.trim()
            if (trimmed.length == 64 && trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
                return trimmed.lowercase()
            }
            return try {
                val bytes = Base64.decode(trimmed, Base64.DEFAULT)
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Throwable) {
                trimmed
            }
        }

        /**
         * Resolves and persists a stable per-device HWID (resolved in the main process before
         * passing the config to `:vkturn` so SharedPreferences never suffer multi-process races).
         */
        fun resolveStableDeviceId(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("vkturn_device_prefs", android.content.Context.MODE_PRIVATE)
            var hwid = prefs.getString("device_hwid", null)
            if (hwid.isNullOrBlank()) {
                val androidId = runCatching {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                }.getOrNull()
                hwid = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
                    ?: UUID.randomUUID().toString()
                prefs.edit().putString("device_hwid", hwid).apply()
            }
            return hwid
        }
    }
}
