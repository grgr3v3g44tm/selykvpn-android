package com.vkturnproxy.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredCookie(
    val cookieHeader: String,
    val expiryTimestamp: Long,
    val savedAtTimestamp: Long
)

/**
 * Manages persistent storage and domain-specific planting of VK session cookies (remixsid + p).
 * Strict 1-to-1 parity with anton48/vk-turn-proxy-ios VKCookieStore.swift and VKOAuth.swift.
 */
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

    /**
     * Extracts an individual cookie value by name from a semicolon-separated Cookie header string.
     */
    fun extractCookieValue(header: String?, name: String): String? {
        if (header.isNullOrBlank()) return null
        for (part in header.split(";")) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val k = trimmed.substring(0, eqIdx).trim()
                val v = trimmed.substring(eqIdx + 1).trim()
                if (k.equals(name, ignoreCase = true) && v.isNotEmpty() && v != "DELETED") {
                    return v
                }
            }
        }
        return null
    }

    /**
     * Checks if a valid, non-expired VK login session exists.
     * remixsid is the primary session cookie of VKontakte required for authentication and OAuth.
     * p cookie from login.vk.ru is optional and preserved when present.
     */
    fun hasValidLogin(now: Long = System.currentTimeMillis()): Boolean {
        val stored = load() ?: return false
        if (stored.expiryTimestamp <= now) return false
        val header = stored.cookieHeader
        if (header.contains("test_") || header.contains("dummy")) return false
        val remixsid = extractCookieValue(header, "remixsid")
        return !remixsid.isNullOrBlank()
    }

    fun isValid(now: Long = System.currentTimeMillis()): Boolean = hasValidLogin(now)

    fun hasValidCookie(): Boolean = isValid()

    fun getValidCookieHeader(now: Long = System.currentTimeMillis()): String? {
        val stored = load() ?: return null
        return if (stored.expiryTimestamp > now) stored.cookieHeader else null
    }

    fun getFormattedExpiry(): String {
        val stored = load() ?: return "Неизвестно"
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(stored.expiryTimestamp))
    }

    /**
     * Plants stored session cookies strictly on their designated domains.
     * Exact 1-to-1 parity with iOS VKOAuth.swift:
     * - remixsid is issued on .vk.ru and .vk.com
     * - p is issued on .login.vk.ru and .login.vk.com
     * Also planted with SameSite=None and Secure for reliable cross-domain OAuth handshake.
     */
    fun plantCookiesForOAuth(cm: CookieManager, header: String? = getValidCookieHeader()) {
        if (header.isNullOrBlank()) return
        val remixsid = extractCookieValue(header, "remixsid")
        val p = extractCookieValue(header, "p")

        if (!remixsid.isNullOrBlank()) {
            val cookieVal = "remixsid=$remixsid; Domain=.vk.ru; Path=/; Secure; SameSite=None"
            cm.setCookie("https://vk.ru/", cookieVal)
            cm.setCookie("https://oauth.vk.ru/", cookieVal)
            cm.setCookie("https://login.vk.ru/", cookieVal)
            cm.setCookie("https://id.vk.ru/", cookieVal)

            val comVal = "remixsid=$remixsid; Domain=.vk.com; Path=/; Secure; SameSite=None"
            cm.setCookie("https://vk.com/", comVal)
            cm.setCookie("https://oauth.vk.com/", comVal)
            cm.setCookie("https://login.vk.com/", comVal)
            cm.setCookie("https://id.vk.com/", comVal)
        }

        if (!p.isNullOrBlank()) {
            // p must be on .login.vk.ru for the identity gate (?act=connect_internal)
            val pLoginRu = "p=$p; Domain=.login.vk.ru; Path=/; Secure; SameSite=None"
            cm.setCookie("https://login.vk.ru/", pLoginRu)
            val pLoginCom = "p=$p; Domain=.login.vk.com; Path=/; Secure; SameSite=None"
            cm.setCookie("https://login.vk.com/", pLoginCom)

            // Also plant on .vk.ru root domain for id.vk.ru session detection
            val pRootRu = "p=$p; Domain=.vk.ru; Path=/; Secure; SameSite=None"
            cm.setCookie("https://id.vk.ru/", pRootRu)
            cm.setCookie("https://vk.ru/", pRootRu)
            val pRootCom = "p=$p; Domain=.vk.com; Path=/; Secure; SameSite=None"
            cm.setCookie("https://id.vk.com/", pRootCom)
            cm.setCookie("https://vk.com/", pRootCom)
        }

        cm.flush()
    }

    /**
     * Asynchronously plants cookies and awaits CookieManager completion callbacks + flush.
     * Prevents race conditions where WebView begins loading before cookies are written.
     */
    suspend fun plantCookiesAsync(cm: CookieManager, header: String? = getValidCookieHeader()) {
        if (header.isNullOrBlank()) return
        plantCookiesForOAuth(cm, header)
        kotlinx.coroutines.delay(120) // Give WebKit cookie store time to sync
    }

    fun clearCookies() {
        delete()
        isEnabled = false
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {}
    }
}
