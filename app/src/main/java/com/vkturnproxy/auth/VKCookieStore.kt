package com.vkturnproxy.auth

import android.content.Context
import android.content.SharedPreferences

data class StoredCookie(
    val cookieHeader: String,
    val expiryTimestamp: Long,
    val savedAtTimestamp: Long
)

object VKCookieStore {
    private const val PREFS_NAME = "vkturn_vkauth"
    private const val KEY_COOKIE_HEADER = "cookie_header"
    private const val KEY_EXPIRY = "cookie_expiry"
    private const val KEY_SAVED_AT = "cookie_saved_at"
    private const val KEY_ENABLED = "vkauth_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_ENABLED, false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            }
        }

    fun save(cookieHeader: String, expiryTimestamp: Long) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_COOKIE_HEADER, cookieHeader)
            .putLong(KEY_EXPIRY, expiryTimestamp)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): StoredCookie? {
        if (!::prefs.isInitialized) return null
        val header = prefs.getString(KEY_COOKIE_HEADER, null) ?: return null
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        return StoredCookie(
            cookieHeader = header,
            expiryTimestamp = expiry,
            savedAtTimestamp = savedAt
        )
    }

    fun delete() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .remove(KEY_COOKIE_HEADER)
            .remove(KEY_EXPIRY)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    fun isValid(now: Long = System.currentTimeMillis()): Boolean {
        val stored = load() ?: return false
        return stored.expiryTimestamp > now
    }

    fun hasValidCookie(): Boolean = isValid()

    fun getValidCookieHeader(now: Long = System.currentTimeMillis()): String? {
        val stored = load() ?: return null
        return if (stored.expiryTimestamp > now) stored.cookieHeader else null
    }

    fun getFormattedExpiry(): String {
        val stored = load() ?: return "Неизвестно"
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(stored.expiryTimestamp))
    }

    fun clearCookies() {
        delete()
        isEnabled = false
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // Ignore webview errors
        }
    }
}
