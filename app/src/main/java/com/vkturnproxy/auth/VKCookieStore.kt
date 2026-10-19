package com.vkturnproxy.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    private const val KEY_ACCESS_TOKEN = "vk_access_token"
    private const val KEY_ACCESS_TOKEN_EXPIRY = "vk_access_token_expiry"

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

    fun saveToken(token: String, expiryTimestamp: Long = System.currentTimeMillis() + 86400L * 1000) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .putLong(KEY_ACCESS_TOKEN_EXPIRY, expiryTimestamp)
            .apply()
    }

    fun getToken(): String? {
        if (!::prefs.isInitialized) return null
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiry = prefs.getLong(KEY_ACCESS_TOKEN_EXPIRY, 0L)
        if (expiry in 1..System.currentTimeMillis()) {
            clearToken()
            return null
        }
        return token
    }

    fun clearToken() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN_EXPIRY)
            .apply()
    }

    suspend fun fetchWebToken(cookieHeader: String): String? = withContext(Dispatchers.IO) {
        val hosts = listOf("login.vk.ru", "login.vk.com")
        val ua = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        for (host in hosts) {
            try {
                val endpoint = "https://$host/?act=web_token"
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Origin", "https://${if (host.contains("vk.ru")) "vk.ru" else "vk.com"}")
                conn.setRequestProperty("Referer", "https://${if (host.contains("vk.ru")) "vk.ru" else "vk.com"}/")
                conn.setRequestProperty("Cookie", cookieHeader)

                val postData = "version=1&app_id=6287487"
                conn.outputStream.use { os ->
                    os.write(postData.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val resp = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                conn.disconnect()

                val map = Gson().fromJson(resp, Map::class.java)
                val data = map["data"] as? Map<*, *>
                val token = data?.get("access_token") as? String
                if (!token.isNullOrBlank()) {
                    val expiresInSec = (data["expires"] as? Number)?.toLong() ?: 86400L
                    val expiry = if (expiresInSec > 0) System.currentTimeMillis() + expiresInSec * 1000L else System.currentTimeMillis() + 86400L * 1000L
                    saveToken(token, expiry)
                    return@withContext token
                }
            } catch (_: Exception) {}
        }
        null
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
        clearToken()
        isEnabled = false
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // Ignore webview errors
        }
    }
}
