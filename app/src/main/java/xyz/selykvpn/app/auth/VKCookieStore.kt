package xyz.selykvpn.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
 */
object VKCookieStore {
    private const val TAG = "VKCookieStore"
    private const val PREFS_NAME = "selykvpn_vkauth_secure"
    private const val KEY_COOKIE_HEADER = "cookie_header"
    private const val KEY_EXPIRY = "cookie_expiry"
    private const val KEY_SAVED_AT = "cookie_saved_at"
    private const val KEY_ENABLED = "vkauth_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = createEncryptedPrefs(context)
        val hasLogin = hasValidLogin()
        Log.d(TAG, "Initialized: hasValidLogin=$hasLogin, expiry=${getFormattedExpiry()}")
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to standard prefs", e)
            context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun isEnabled(): Boolean {
        if (!::prefs.isInitialized) return true
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun saveSession(rawCookieString: String, expiryTimestamp: Long) {
        if (!::prefs.isInitialized) return
        val normalized = normalizeCookieString(rawCookieString)
        prefs.edit()
            .putString(KEY_COOKIE_HEADER, normalized)
            .putLong(KEY_EXPIRY, expiryTimestamp)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Saved VK session cookies, expiry=${formatTimestamp(expiryTimestamp)}")
    }

    fun getValidCookieHeader(): String? {
        if (!::prefs.isInitialized) return null
        if (!isEnabled()) return null

        val header = prefs.getString(KEY_COOKIE_HEADER, null) ?: return null
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        val now = System.currentTimeMillis()

        if (expiry in 1..now) {
            Log.w(TAG, "Stored cookies expired at ${formatTimestamp(expiry)}, now=$now")
            clearSession()
            return null
        }
        return header
    }

    fun hasValidLogin(): Boolean {
        return getValidCookieHeader() != null
    }

    fun clearSession() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .remove(KEY_COOKIE_HEADER)
            .remove(KEY_EXPIRY)
            .remove(KEY_SAVED_AT)
            .apply()
        clearWebViewCookies()
        Log.i(TAG, "Cleared VK session")
    }

    fun clearWebViewCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear WebView cookies", e)
        }
    }

    fun plantCookiesIntoWebView() {
        val header = getValidCookieHeader() ?: return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)

            val cookies = header.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            for (cookie in cookies) {
                cm.setCookie("https://vk.ru", "$cookie; Domain=.vk.ru; Path=/; Secure; SameSite=None")
                cm.setCookie("https://vk.com", "$cookie; Domain=.vk.com; Path=/; Secure; SameSite=None")
                cm.setCookie("https://login.vk.ru", "$cookie; Domain=.login.vk.ru; Path=/; Secure; SameSite=None")
                cm.setCookie("https://oauth.vk.com", "$cookie; Domain=.oauth.vk.com; Path=/; Secure; SameSite=None")
            }
            cm.flush()
            Log.i(TAG, "Planted ${cookies.size} cookies into WebView")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to plant cookies into WebView", e)
        }
    }

    fun getFormattedExpiry(): String {
        if (!::prefs.isInitialized) return "Н/Д"
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        return if (expiry > 0) formatTimestamp(expiry) else "Бессрочно"
    }

    private fun normalizeCookieString(raw: String): String {
        val parts = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val map = linkedMapOf<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                map[name] = value
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun formatTimestamp(ts: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
