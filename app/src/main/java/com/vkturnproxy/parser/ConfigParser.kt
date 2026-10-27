package com.vkturnproxy.parser

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vkturnproxy.model.ProxyMode
import com.vkturnproxy.model.ServerConfig

object ConfigParser {

    fun parse(input: String): ServerConfig? {
        val trimmed = input.trim()
        if (trimmed.startsWith("vkturnproxy://")) {
            return parseVkTurnUri(trimmed)
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJson(trimmed)
        }
        if (trimmed.contains("[Interface]", ignoreCase = true)) {
            return parseWireGuardConf(trimmed)
        }
        return null
    }

    fun parseVkTurnUri(uriString: String): ServerConfig? {
        return try {
            val uri = Uri.parse(uriString)
            val data = uri.getQueryParameter("data") ?: return null
            val jsonBytes = decodeBase64Flexible(data) ?: return null
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            parseJson(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Декодит base64 в любом из вариантов: стандартный или url-safe, с padding
     * или без.
     *
     * Сервер выдаёт ключ в формате base64url БЕЗ padding — так же, как это делает
     * эталонный генератор ссылок iOS-приложения (quick_link.py:
     * base64.urlsafe_b64encode(...).rstrip("=")), потому что «+» и «/» в
     * query-параметре URL неоднозначны. Старые ключи лежат в стандартном
     * base64 с «=» — принимаем оба, иначе смена формата тихо ломает импорт.
     */
    private fun decodeBase64Flexible(input: String): ByteArray? {
        val variants = intArrayOf(
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in variants) {
            try {
                return Base64.decode(input, flags)
            } catch (_: Exception) {
                // пробуем следующий вариант
            }
        }
        // Последний шанс: нормализуем url-safe → standard и дополняем padding.
        return try {
            val norm = input.replace('-', '+').replace('_', '/')
            val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    fun parseJson(jsonString: String): ServerConfig? {
        return try {
            val root = Gson().fromJson(jsonString, JsonObject::class.java) ?: return null
            val settings = if (root.has("settings")) root.getAsJsonObject("settings") else root

            val name = settings.get("serverName")?.asString
                ?: settings.get("name")?.asString
                ?: "Server"
            val vkLink = settings.get("vkLink")?.asString
                ?: settings.get("vk_link")?.asString
                ?: ""
            // Явная заглушка от сервера: ключ выдан, но сам звонок VK ещё не
            // создан. Раньше такой импорт молча давал конфигурацию, к которой
            // нельзя было подключиться, и пользователь не понимал, что делать.
            val needsCallLink = vkLink.trim()
                .equals(ServerConfig.PLACEHOLDER_CALL_LINK, ignoreCase = true)
            val peerAddress = settings.get("peerAddress")?.asString
                ?: settings.get("peer_addr")?.asString
                ?: ""
            val numConns = settings.get("numConnections")?.asInt
                ?: settings.get("num_conns")?.asInt
                ?: 30

            val useDTLS = settings.get("useDTLS")?.asBoolean ?: true
            val useUDP = settings.get("useUDP")?.asBoolean
                ?: settings.get("use_udp")?.asBoolean
                ?: true
            val useSrtp = settings.get("useSrtp")?.asBoolean ?: true
            val useWrap = settings.get("useWrap")?.asBoolean ?: false
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

            // WireGuard
            val privateKey = settings.get("privateKey")?.asString
                ?: settings.get("private_key")?.asString
                ?: ""
            val peerPublicKey = settings.get("peerPublicKey")?.asString
                ?: settings.get("peer_public_key")?.asString
                ?: ""
            val presharedKey = settings.get("presharedKey")?.asString
                ?: settings.get("preshared_key")?.asString
                ?: ""
            val tunnelAddress = settings.get("tunnelAddress")?.asString
                ?: settings.get("tunnel_address")?.asString
                ?: "10.77.77.2/24"
            val dnsServers = settings.get("dnsServers")?.asString
                ?: settings.get("dns_servers")?.asString
                ?: "1.1.1.1, 77.88.8.8, 10.77.77.1"
            val mtu = settings.get("mtu")?.asInt ?: if (useUDP) 1360 else 1280

            val useCookieAuth = settings.get("useCookieAuth")?.asBoolean
                ?: settings.get("use_cookie_auth")?.asBoolean
                ?: false
            val uplinkPaceKiB = settings.get("uplinkPaceKiB")?.asInt
                ?: settings.get("uplink_pace_kib")?.asInt
                ?: 0
            val uplinkPaceBurstKiB = settings.get("uplinkPaceBurstKiB")?.asInt
                ?: settings.get("uplink_pace_burst_kib")?.asInt
                ?: 16
            val forceLegacyCaptcha = settings.get("forceLegacyCaptcha")?.asBoolean
                ?: settings.get("force_legacy_captcha")?.asBoolean
                ?: false
            val memstatsFastTicks = settings.get("memstatsFastTicks")?.asBoolean
                ?: settings.get("memstats_fast_ticks")?.asBoolean
                ?: false

            val mode = when {
                useWrapA -> ProxyMode.SRTP_WRAP_A
                useWrapS -> ProxyMode.SRTP_WRAP_S
                useWrap -> ProxyMode.SRTP_WRAP
                useSrtp -> ProxyMode.SRTP
                else -> ProxyMode.LEGACY
            }

            ServerConfig(
                name = name,
                mode = mode,
                vkLink = vkLink,
                needsCallLink = needsCallLink,
                peerAddress = peerAddress,
                numConnections = numConns,
                useUDP = useUDP,
                useDTLS = useDTLS,
                useSrtp = useSrtp,
                useWrap = useWrap,
                useWrapA = useWrapA,
                wrapKeyHex = wrapKeyHex,
                serverPassword = serverPassword,
                deviceId = deviceId,
                useCookieAuth = useCookieAuth,
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
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseWireGuardConf(conf: String): ServerConfig {
        val config = ServerConfig(name = "WireGuard Import")
        var currentSection = ""

        for (rawLine in conf.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).lowercase()
                continue
            }

            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when (currentSection) {
                "interface" -> {
                    when (key) {
                        "privatekey" -> config.privateKey = value
                        "address" -> config.tunnelAddress = value
                        "dns" -> config.dnsServers = value
                        "mtu" -> config.mtu = value.toIntOrNull() ?: 1280
                    }
                }
                "peer" -> {
                    when (key) {
                        "publickey" -> config.peerPublicKey = value
                        "presharedkey" -> config.presharedKey = value
                        "endpoint" -> config.peerAddress = value
                    }
                }
            }
        }
        return config
    }
}
