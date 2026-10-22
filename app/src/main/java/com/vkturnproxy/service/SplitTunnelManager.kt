package com.vkturnproxy.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.util.Log

enum class SplitTunnelMode {
    DISALLOW_SELECTED, // Blacklist: excluded apps bypass VPN
    ALLOW_SELECTED     // Whitelist: only selected apps go through VPN
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val isSystem: Boolean = false
)

class SplitTunnelManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vkturn_split_tunnel", Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var mode: SplitTunnelMode
        get() = when (prefs.getString("mode", "DISALLOW_SELECTED")) {
            "ALLOW_SELECTED" -> SplitTunnelMode.ALLOW_SELECTED
            else -> SplitTunnelMode.DISALLOW_SELECTED
        }
        set(value) = prefs.edit().putString("mode", value.name).apply()

    fun getSelectedPackages(): Set<String> {
        return prefs.getStringSet("selected_packages", emptySet()) ?: emptySet()
    }

    fun setSelectedPackages(packages: Set<String>) {
        prefs.edit().putStringSet("selected_packages", packages).apply()
    }

    fun togglePackage(packageName: String) {
        val current = getSelectedPackages().toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        setSelectedPackages(current)
    }

    fun applyToBuilder(builder: VpnService.Builder, myPackageName: String) {
        val splitEnabled = isEnabled
        val splitMode = mode
        val selected = getSelectedPackages()

        if (splitEnabled && splitMode == SplitTunnelMode.ALLOW_SELECTED) {
            // Whitelist mode: ONLY apps explicitly added via addAllowedApplication go through VPN.
            Log.i("SplitTunnelManager", "Applying split tunneling (mode=ALLOW_SELECTED, count=${selected.size})")
            for (pkg in selected) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w("SplitTunnelManager", "Package not found: $pkg")
                } catch (e: Exception) {
                    Log.w("SplitTunnelManager", "Cannot allow $pkg: ${e.message}")
                }
            }
        } else {
            // Default / Blacklist mode: all apps go through VPN EXCEPT disallowed apps.
            // Note: The VPN app itself is NOT disallowed, allowing in-app speed tests and diagnostics
            // to measure real VPN performance. Outbound carrier/TURN sockets are protected via VpnService.protect(fd).
            if (splitEnabled && splitMode == SplitTunnelMode.DISALLOW_SELECTED) {
                Log.i("SplitTunnelManager", "Applying split tunneling (mode=DISALLOW_SELECTED, count=${selected.size})")
                for (pkg in selected) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w("SplitTunnelManager", "Package not found: $pkg")
                    } catch (e: Exception) {
                        Log.w("SplitTunnelManager", "Cannot disallow $pkg: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        val POPULAR_RU_APPS = setOf(
            "ru.gosuslugi.gatp",         // Госуслуги
            "ru.sberbankmobile",          // СберБанк
            "com.idamob.tinkoff.android", // Т-Банк (Тинькофф)
            "ru.vtb24.mobilebanking",     // ВТБ
            "ru.alfabank.mobile.android", // Альфа-Банк
            "com.vkontakte.android",      // VK
            "ru.yandex.searchplugin",     // Яндекс
            "ru.yandex.yandexnavi",       // Яндекс Навигатор
            "ru.yandex.taxi",             // Яндекс Go
            "ru.ozon.app.android",        // Ozon
            "com.wildberries.work",       // Wildberries
            "ru.mts.mymts",               // Мой МТС
            "ru.megafon.mlk",             // МегаФон
            "ru.beeline.services",        // билайн
            "ru.tele2.mytele2",           // t2 (Tele2)
            "ru.nspk.mirpay"              // Mir Pay
        )

        fun loadInstalledApps(pm: PackageManager): List<AppInfo> {
            val list = mutableListOf<AppInfo>()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                // Ignore self
                val label = pm.getApplicationLabel(app).toString()
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(
                    AppInfo(
                        packageName = app.packageName,
                        label = label,
                        icon = pm.getApplicationIcon(app),
                        isSystem = isSys
                    )
                )
            }
            return list.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        }
    }
}
