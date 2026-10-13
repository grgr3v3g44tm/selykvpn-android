package com.vkturnproxy.utils

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkAddressInfo(
    val interfaceName: String,
    val ipAddress: String,
    val type: String // "Wi-Fi Hotspot", "USB Модем", "Wi-Fi / LAN"
)

object NetworkUtils {
    fun getHotspotAndLanAddresses(): List<NetworkAddressInfo> {
        val result = mutableListOf<NetworkAddressInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("p2p") ||
                    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                    name.startsWith("wwan") || name.startsWith("radio")) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val type = when {
                            name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") -> "Wi-Fi Точка доступа"
                            name.startsWith("rndis") || name.startsWith("usb") -> "USB-модем"
                            name.startsWith("wlan") -> "Wi-Fi / Локальная сеть"
                            name.startsWith("eth") -> "Ethernet"
                            else -> "Интерфейс $name"
                        }
                        result.add(NetworkAddressInfo(intf.name, host, type))
                    }
                }
            }
        } catch (_: Exception) {}

        // Sort so that Hotspot (ap+) or standard 192.168.43.1 appears first
        return result.sortedByDescending {
            when {
                it.ipAddress == "192.168.43.1" -> 100
                it.interfaceName.startsWith("ap") -> 90
                it.interfaceName.startsWith("rndis") -> 80
                it.interfaceName.startsWith("wlan") -> 70
                else -> 50
            }
        }
    }

    fun getPrimaryLanIp(): String {
        val list = getHotspotAndLanAddresses()
        return list.firstOrNull()?.ipAddress ?: "192.168.43.1"
    }
}
