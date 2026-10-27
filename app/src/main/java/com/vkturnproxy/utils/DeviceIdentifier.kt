package com.vkturnproxy.utils

import android.content.Context
import android.os.Build
import java.util.UUID

object DeviceIdentifier {
    private const val PREFS_NAME = "vkturn_device_prefs"
    private const val KEY_HWID = "device_hwid"

    @Synchronized
    fun getHwid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var hwid = prefs.getString(KEY_HWID, null)
        if (hwid.isNullOrBlank()) {
            hwid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_HWID, hwid).apply()
        }
        return hwid
    }

    fun getDeviceModel(): String {
        val rawMfg = Build.MANUFACTURER.orEmpty().trim()
        val mfg = if (rawMfg.isNotEmpty()) {
            rawMfg.substring(0, 1).uppercase() + rawMfg.substring(1)
        } else {
            ""
        }
        val model = Build.MODEL.orEmpty().trim()

        return if (model.startsWith(mfg, ignoreCase = true)) {
            model
        } else if (mfg.isNotEmpty()) {
            "$mfg $model".trim()
        } else {
            model
        }.ifEmpty { "Android Device" }
    }
}
