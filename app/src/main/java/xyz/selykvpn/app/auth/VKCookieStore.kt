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

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = createEncryptedPrefs(context.applicationContext)
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
        val store = prefs ?: return false
        return store.getBoolean(KEY_ENABLED, hasStoredSession())
    }

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun saveSession(rawCookieString: String, expiryTimestamp: Long) {
        val store = prefs ?: return
        val normalized = normalizeCookieString(rawCookieString)
        store.edit()
            .putString(KEY_COOKIE_HEADER, normalized)
            .putLong(KEY_EXPIRY, expiryTimestamp)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putBoolean(KEY_ENABLED, true)
            .apply()
        Log.i(TAG, "Saved VK session cookies, expiry=${formatTimestamp(expiryTimestamp)}")
    }

    fun getStoredCookieHeaderIgnoreEnabled(): String? {
        val store = prefs ?: return null
        val header = store.getString(KEY_COOKIE_HEADER, null) ?: return null
        val expiry = store.getLong(KEY_EXPIRY, 0L)
        val now = System.currentTimeMillis()
        if (expiry in 1..now) {
            Log.w(TAG, "Stored cookies expired at ${formatTimestamp(expiry)}, now=$now")
            clearSession()
            return null
        }
        val remixsid = extractCookieValue(header, "remixsid")
        if (remixsid.isNullOrBlank() || header.contains("test_") || header.contains("dummy")) {
            return null
        }
        return header
    }

    fun getValidCookieHeader(): String? {
        if (!isEnabled()) return null
        return getStoredCookieHeaderIgnoreEnabled()
    }

    fun hasStoredSession(): Boolean {
        return getStoredCookieHeaderIgnoreEnabled() != null
    }

    fun hasValidLogin(): Boolean {
        return getValidCookieHeader() != null
    }

    fun clearSession() {
        prefs?.edit()
            ?.remove(KEY_COOKIE_HEADER)
            ?.remove(KEY_EXPIRY)
            ?.remove(KEY_SAVED_AT)
            ?.putBoolean(KEY_ENABLED, false)
            ?.apply()
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

    data class VkSessionCookies(
        val remixsid: String? = null,
        val p: String? = null,
        val remixstid: String? = null,
        val remixstlid: String? = null
    ) {
        fun toCookieHeader(): String {
            val parts = mutableListOf<String>()
            if (!remixsid.isNullOrBlank()) parts.add("remixsid=$remixsid")
            if (!p.isNullOrBlank()) parts.add("p=$p")
            if (!remixstid.isNullOrBlank()) parts.add("remixstid=$remixstid")
            if (!remixstlid.isNullOrBlank()) parts.add("remixstlid=$remixstlid")
            return parts.joinToString("; ")
        }
    }

    fun extractCookiesFromManager(cm: CookieManager): Pair<String?, String?> {
        val full = extractFullCookiesFromManager(cm)
        return Pair(full.remixsid, full.p)
    }

    fun extractFullCookiesFromManager(cm: CookieManager): VkSessionCookies {
        val domains = listOf(
            "https://vk.ru/",
            "https://m.vk.ru/",
            "https://id.vk.ru/",
            "https://login.vk.ru/",
            "https://oauth.vk.ru/",
            "https://vk.com/",
            "https://m.vk.com/",
            "https://id.vk.com/",
            "https://login.vk.com/",
            "https://oauth.vk.com/"
        )

        var remixsid: String? = null
        var p: String? = null
        var remixstid: String? = null
        var remixstlid: String? = null

        for (domain in domains) {
            val c = cm.getCookie(domain) ?: continue
            if (remixsid == null) {
                remixsid = extractCookieValue(c, "remixsid")
            }
            if (p == null) {
                p = extractCookieValue(c, "p")
            }
            if (remixstid == null) {
                remixstid = extractCookieValue(c, "remixstid")
            }
            if (remixstlid == null) {
                remixstlid = extractCookieValue(c, "remixstlid")
            }
            if (remixsid != null && p != null && remixstid != null && remixstlid != null) break
        }
        return VkSessionCookies(
            remixsid = remixsid,
            p = p,
            remixstid = remixstid,
            remixstlid = remixstlid
        )
    }

    fun plantCookiesIntoWebView(overrideHeader: String? = null) {
        val header = overrideHeader ?: getStoredCookieHeaderIgnoreEnabled() ?: return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)

            val remixsid = extractCookieValue(header, "remixsid")
            val p = extractCookieValue(header, "p")
            val remixstid = extractCookieValue(header, "remixstid")
            val remixstlid = extractCookieValue(header, "remixstlid")

            fun plantAcrossSubdomains(name: String, value: String) {
                val ruVal = "$name=$value; Domain=.vk.ru; Path=/; Secure; SameSite=None"
                cm.setCookie("https://vk.ru/", ruVal)
                cm.setCookie("https://m.vk.ru/", ruVal)
                cm.setCookie("https://oauth.vk.ru/", ruVal)
                cm.setCookie("https://login.vk.ru/", ruVal)
                cm.setCookie("https://id.vk.ru/", ruVal)

                val comVal = "$name=$value; Domain=.vk.com; Path=/; Secure; SameSite=None"
                cm.setCookie("https://vk.com/", comVal)
                cm.setCookie("https://m.vk.com/", comVal)
                cm.setCookie("https://oauth.vk.com/", comVal)
                cm.setCookie("https://login.vk.com/", comVal)
                cm.setCookie("https://id.vk.com/", comVal)
            }

            if (!remixsid.isNullOrBlank()) {
                plantAcrossSubdomains("remixsid", remixsid)
            }
            if (!p.isNullOrBlank()) {
                cm.setCookie("https://login.vk.ru/", "p=$p; Domain=.login.vk.ru; Path=/; Secure; SameSite=None")
                cm.setCookie("https://login.vk.com/", "p=$p; Domain=.login.vk.com; Path=/; Secure; SameSite=None")
                plantAcrossSubdomains("p", p)
            }
            if (!remixstid.isNullOrBlank()) {
                plantAcrossSubdomains("remixstid", remixstid)
            }
            if (!remixstlid.isNullOrBlank()) {
                plantAcrossSubdomains("remixstlid", remixstlid)
            }
            cm.flush()
            Log.i(TAG, "Planted VK cookies for OAuth: remixsid=${remixsid != null}, p=${p != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to plant cookies into WebView", e)
        }
    }

    fun getFormattedExpiry(): String {
        val store = prefs ?: return "Н/Д"
        val expiry = store.getLong(KEY_EXPIRY, 0L)
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
