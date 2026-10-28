package com.vkturnproxy.model

import android.util.Base64
import com.google.gson.Gson
import java.util.UUID

enum class ProxyMode {
    SRTP,
    SRTP_WRAP,
    SRTP_WRAP_A,
    SRTP_WRAP_S,
    LEGACY
}

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Server",
    var mode: ProxyMode = ProxyMode.SRTP,
    var vkLink: String = "",
    // Ключ выдан сервером, но звонок VK ещё не создан: в vkLink лежит
    // явная заглушка ServerConfig.PLACEHOLDER_CALL_LINK. Пока флаг не снят,
    // туннель запускаться не должен — сначала нужно создать звонок.
    var needsCallLink: Boolean = false,
    var peerAddress: String = "",
    var numConnections: Int = 30,
    var useUDP: Boolean = false,
    var useDTLS: Boolean = true,
    var useSrtp: Boolean = true,
    var useWrap: Boolean = false,
    var useWrapA: Boolean = false,
    var wrapKeyHex: String = "",
    var serverPassword: String = "",
    var deviceId: String = "",
    var wrapSProfile: String = "auto",

    var useCookieAuth: Boolean = false,
    var uplinkPaceKiB: Int = 0,
    var uplinkPaceBurstKiB: Int = 16,
    var forceLegacyCaptcha: Boolean = false,
    var memstatsFastTicks: Boolean = false,

    // WireGuard parameters
    var privateKey: String = "",
    var peerPublicKey: String = "",
    var presharedKey: String = "",
    var tunnelAddress: String = "10.77.77.2/24",
    var dnsServers: String = "1.1.1.1, 77.88.8.8, 10.77.77.1",
    var mtu: Int = 1280
) {
    fun getParsedCookieLinks(): List<String> {
        return vkLink.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("placeholder") }
    }

    fun cookieConnCap(callLinksCount: Int): Int {
        return (callLinksCount * 20).coerceIn(2, 50)
    }

    val effectiveNumConnections: Int
        get() {
            if (!useCookieAuth) return numConnections.coerceIn(1, 60)
            val links = getParsedCookieLinks()
            val cap = cookieConnCap(links.size.coerceAtLeast(1))
            return minOf(numConnections.coerceIn(1, 60), cap)
        }

    fun toProxyConfigJson(): String {
        val map = mutableMapOf<String, Any>()
        val cookieLinks = getParsedCookieLinks()
        val cleanVkLink = cookieLinks.firstOrNull { it.contains("call/join/") }
            ?: cookieLinks.firstOrNull()
            ?: vkLink.trim()
        map["vk_link"] = cleanVkLink
        map["vk_cookie_links"] = cookieLinks
        map["peer_addr"] = peerAddress
        map["use_dtls"] = useDTLS
        map["use_udp"] = useUDP
        map["mtu"] = mtu.coerceIn(1200, 1280)
        map["num_conns"] = effectiveNumConnections
        map["use_cookie_auth"] = useCookieAuth
        if (uplinkPaceKiB > 0) {
            map["uplink_pace_kib"] = uplinkPaceKiB
            map["uplink_pace_burst_kib"] = uplinkPaceBurstKiB
        }
        map["force_legacy_captcha"] = forceLegacyCaptcha
        map["memstats_fast_ticks"] = memstatsFastTicks

        val stableDeviceId = if (deviceId.isNotBlank()) {
            deviceId
        } else {
            try {
                com.vkturnproxy.utils.DeviceIdentifier.getHwid(com.vkturnproxy.VkTurnApp.instance)
            } catch (_: Exception) {
                id.replace("-", "").take(16)
            }
        }

        when (mode) {
            ProxyMode.SRTP -> {
                map["use_srtp"] = true
                map["use_wrap"] = false
            }
            ProxyMode.SRTP_WRAP -> {
                map["use_srtp"] = true
                map["use_wrap"] = true
                map["wrap_key_hex"] = wrapKeyHex
            }
            ProxyMode.SRTP_WRAP_A -> {
                map["use_srtp"] = true
                map["use_wrap"] = true
                map["use_wrap_a"] = true
                map["wrap_a_password"] = serverPassword
                map["device_id"] = stableDeviceId
            }
            ProxyMode.SRTP_WRAP_S -> {
                map["use_srtp"] = true
                map["use_wrap"] = true
                map["wrap_key_hex"] = wrapKeyHex
                map["obf_profile"] = wrapSProfile
                map["client_id"] = stableDeviceId
            }
            ProxyMode.LEGACY -> {
                map["use_srtp"] = false
                map["use_wrap"] = false
            }
        }

        return Gson().toJson(map)
    }

    fun toUAPIConfig(): String {
        if (mode == ProxyMode.SRTP_WRAP_A) {
            return ""
        }
        val sb = StringBuilder()
        sb.append("private_key=").append(keyToHex(privateKey)).append("\n")
        sb.append("replace_peers=true\n")
        sb.append("public_key=").append(keyToHex(peerPublicKey)).append("\n")
        sb.append("endpoint=").append(peerAddress).append("\n")
        sb.append("persistent_keepalive_interval=25\n")
        sb.append("allowed_ip=0.0.0.0/0\n")
        sb.append("allowed_ip=::/0\n")
        if (presharedKey.isNotBlank()) {
            sb.append("preshared_key=").append(keyToHex(presharedKey)).append("\n")
        }
        return sb.toString()
    }

    companion object {
        /**
         * Явная заглушка вместо пустой ссылки на звонок VK.
         *
         * Сервер выдаёт ключ сразу, но сам звонок ещё не создан. Раньше это
         * выглядело как обычная пустая ссылка, и импорт молча оставлял
         * конфигурацию, к которой нельзя подключиться. Заглушка распознаётся
         * при импорте и превращается в флаг needsCallLink.
         */
        const val PLACEHOLDER_CALL_LINK = "vkturnproxy://placeholder"

        fun keyToHex(key: String): String {
            val trimmed = key.trim()
            if (trimmed.length == 64 && trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
                return trimmed.lowercase()
            }
            return try {
                val bytes = Base64.decode(trimmed, Base64.DEFAULT)
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
        }
    }
}

/**
 * Реальная ссылка на звонок VK — та, которую умеет использовать ядро.
 * Заглушка «vkturnproxy://placeholder» реальной ссылкой не является.
 */
fun ServerConfig.hasRealVKCallLink(): Boolean {
    if (vkLink.isBlank()) return false
    return vkLink.lines().any { line ->
        line.trim().contains("call/join/")
    }
}

/**
 * Ссылка на звонок ещё не создана: ключ получен с заглушкой, и туннель не
 * может стартовать, пока пользователь не создаст звонок.
 */
fun ServerConfig.needsVKCallLink(): Boolean = needsCallLink && !hasRealVKCallLink()

/**
 * Подставляет реальную ссылку на звонок вместо заглушки.
 * Очищает ссылку от лишних переводов строк и пробелов.
 */
fun ServerConfig.withCallLink(newLink: String): ServerConfig {
    val clean = newLink.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") } ?: newLink.trim()
    return copy(vkLink = clean, needsCallLink = false)
}

fun ServerConfig.hasValidVKCallLink(): Boolean = hasRealVKCallLink()

fun hasValidVKCallLink(link: String?): Boolean {
    if (link.isNullOrBlank()) return false
    return link.lines().any { line ->
        line.trim().contains("call/join/")
    }
}

