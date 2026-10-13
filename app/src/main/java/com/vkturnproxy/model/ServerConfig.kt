package com.vkturnproxy.model

import android.util.Base64
import com.google.gson.Gson
import java.util.UUID

enum class ProxyMode {
    SRTP,
    SRTP_WRAP,
    SRTP_WRAP_A,
    SRTP_WRAP_S,
    CSQTT,
    LEGACY
}

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Server",
    var mode: ProxyMode = ProxyMode.SRTP,
    var vkLink: String = "",
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
    var dnsServers: String = "10.77.77.1",
    var mtu: Int = 1280
) {
    fun toProxyConfigJson(): String {
        val map = mutableMapOf<String, Any>()
        map["vk_link"] = vkLink
        map["peer_addr"] = peerAddress
        map["use_dtls"] = useDTLS
        map["use_udp"] = useUDP
        map["num_conns"] = numConnections.coerceIn(1, 60)
        map["use_cookie_auth"] = useCookieAuth
        if (uplinkPaceKiB > 0) {
            map["uplink_pace_kib"] = uplinkPaceKiB
            map["uplink_pace_burst_kib"] = uplinkPaceBurstKiB
        }
        map["force_legacy_captcha"] = forceLegacyCaptcha
        map["memstats_fast_ticks"] = memstatsFastTicks

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
                map["device_id"] = deviceId.ifEmpty { id.replace("-", "").take(16) }
            }
            ProxyMode.SRTP_WRAP_S -> {
                map["use_srtp"] = true
                map["use_wrap"] = true
                map["wrap_key_hex"] = wrapKeyHex
                map["obf_profile"] = wrapSProfile
                map["client_id"] = deviceId.ifEmpty { id.replace("-", "").take(16) }
            }
            ProxyMode.CSQTT -> {
                map["use_csqtt"] = true
                map["csqtt_password"] = serverPassword
                map["csqtt_device_id"] = deviceId.ifEmpty { id.replace("-", "").take(16) }
            }
            ProxyMode.LEGACY -> {
                map["use_srtp"] = false
                map["use_wrap"] = false
            }
        }

        return Gson().toJson(map)
    }

    fun toUAPIConfig(): String {
        if (mode == ProxyMode.SRTP_WRAP_A || mode == ProxyMode.CSQTT) {
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
