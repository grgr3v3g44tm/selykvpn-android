package xyz.selykvpn.app.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.serialization.Serializable
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.SecureBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredCookie(
    val cookieHeader: String,
    val expiryTimestamp: Long,
    val savedAtTimestamp: Long
)

@Serializable
private data class PersistedVkSession(
    val cookieHeader: String? = null,
    val expiryTimestamp: Long = 0L,
    val savedAtTimestamp: Long = 0L,
    val enabled: Boolean = false,
)

/**
 * Manages persistent hardware-encrypted storage and domain-specific planting of VK session cookies (remixsid + p).
 */
object VKCookieStore {
    private const val TAG = "VKCookieStore"
    private const val PREFS_NAME = "selykvpn_vkauth_secure"
    private const val FILE_NAME = "vk_session.enc"

    private val lock = Any()
    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var session: PersistedVkSession? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (appContext == null) appContext = ctx
            if (session != null) return
            session = loadOrMigrateLocked(ctx)
        }
        val hasLogin = hasValidLogin()
        Log.d(TAG, "Initialized: hasValidLogin=$hasLogin, expiry=${getFormattedExpiry()}")
    }

    private fun sessionFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun loadOrMigrateLocked(ctx: Context): PersistedVkSession {
        val file = sessionFile(ctx)
        val encryptedJson = SecureBox.readEncryptedOrMigrate(file)
        if (!encryptedJson.isNullOrBlank()) {
            return runCatching { AppJson.decodeFromString<PersistedVkSession>(encryptedJson) }
                .getOrDefault(PersistedVkSession())
        }
        // Check and wipe any legacy fallback plaintext prefs if present from prior releases.
        val legacyFallback = ctx.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        val legacyHeader = legacyFallback.getString("cookie_header", null)
        val legacyExpiry = legacyFallback.getLong("cookie_expiry", 0L)
        val legacySavedAt = legacyFallback.getLong("cookie_saved_at", 0L)
        val legacyEnabled = legacyFallback.getBoolean("vkauth_enabled", !legacyHeader.isNullOrBlank())
        if (legacyFallback.all.isNotEmpty()) {
            legacyFallback.edit().clear().apply()
            runCatching { ctx.deleteSharedPreferences(PREFS_NAME + "_fallback") }
        }
        if (!legacyHeader.isNullOrBlank()) {
            val migrated = PersistedVkSession(
                cookieHeader = normalizeCookieString(legacyHeader),
                expiryTimestamp = legacyExpiry,
                savedAtTimestamp = legacySavedAt,
                enabled = legacyEnabled,
            )
            persistLocked(migrated)
            return migrated
        }
        return PersistedVkSession()
    }

    private fun persistLocked(next: PersistedVkSession) {
        session = next
        val ctx = appContext ?: return
        runCatching {
            SecureBox.writeEncryptedAtomically(
                sessionFile(ctx),
                AppJson.encodeToString(PersistedVkSession.serializer(), next),
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to persist encrypted VK session", e)
        }
    }

    fun isEnabled(): Boolean = synchronized(lock) {
        val s = session ?: return false
        s.enabled || !s.cookieHeader.isNullOrBlank() && s.enabled
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            val cur = session ?: PersistedVkSession()
            persistLocked(cur.copy(enabled = enabled))
        }
    }

    fun saveSession(rawCookieString: String, expiryTimestamp: Long) {
        val normalized = normalizeCookieString(rawCookieString)
        synchronized(lock) {
            persistLocked(
                PersistedVkSession(
                    cookieHeader = normalized,
                    expiryTimestamp = expiryTimestamp,
                    savedAtTimestamp = System.currentTimeMillis(),
                    enabled = true,
                )
            )
        }
        Log.i(TAG, "Saved VK session cookies, expiry=${formatTimestamp(expiryTimestamp)}")
    }

    fun getStoredCookieHeaderIgnoreEnabled(): String? {
        val (header, expiry) = synchronized(lock) {
            val s = session ?: return null
            s.cookieHeader to s.expiryTimestamp
        }
        if (header.isNullOrBlank()) return null
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
        synchronized(lock) {
            persistLocked(PersistedVkSession(enabled = false))
        }
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
        val expiry = synchronized(lock) { session?.expiryTimestamp ?: 0L }
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
        return dateFormat.get()?.format(Date(ts)).orEmpty()
    }
}
