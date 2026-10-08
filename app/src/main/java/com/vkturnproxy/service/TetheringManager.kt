package com.vkturnproxy.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

object TetheringManager {
    private const val TAG = "TetheringManager"

    private val TETHER_INTERFACES = listOf("ap+", "softap+", "rndis+", "wlan+", "usb+", "bnep+", "bt-pan+")

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    fun applyRules(enable: Boolean, tunnelIface: String = "tun+", dnsServer: String = "1.1.1.1") {
        Thread {
            try {
                val p = try {
                    Runtime.getRuntime().exec("su")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to execute su: ${e.message}")
                    return@Thread
                }
                val os = DataOutputStream(p.outputStream)
                val cmds = mutableListOf<String>()

                for (iface in TETHER_INTERFACES) {
                    // Remove old rules first
                    cmds.add("iptables -t nat -D PREROUTING -i $iface -p udp --dport 53 -j DNAT --to-destination $dnsServer:53 2>/dev/null")
                    cmds.add("iptables -t nat -D PREROUTING -i $iface -p tcp --dport 53 -j DNAT --to-destination $dnsServer:53 2>/dev/null")

                    if (enable) {
                        cmds.add("iptables -t nat -I PREROUTING -i $iface -p udp --dport 53 -j DNAT --to-destination $dnsServer:53")
                        cmds.add("iptables -t nat -I PREROUTING -i $iface -p tcp --dport 53 -j DNAT --to-destination $dnsServer:53")
                    }
                }

                // Global Forwarding and MASQUERADE
                cmds.add("iptables -D FORWARD -j ACCEPT 2>/dev/null")
                cmds.add("iptables -t nat -D POSTROUTING -o $tunnelIface -j MASQUERADE 2>/dev/null")

                if (enable) {
                    cmds.add("iptables -I FORWARD -j ACCEPT")
                    cmds.add("iptables -t nat -I POSTROUTING -o $tunnelIface -j MASQUERADE")
                    cmds.add("sysctl -w net.ipv4.ip_forward=1 2>/dev/null")
                    cmds.add("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null")
                    Log.i(TAG, "Root tethering iptables rules applied for $tunnelIface (DNS -> $dnsServer)")
                } else {
                    Log.i(TAG, "Root tethering iptables rules cleared")
                }

                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
                p.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Error applying tethering rules", e)
            }
        }.start()
    }
}
