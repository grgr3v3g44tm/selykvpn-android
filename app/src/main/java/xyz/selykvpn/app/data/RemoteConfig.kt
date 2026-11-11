package xyz.selykvpn.app.data

import android.content.Context
import xyz.selykvpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.net.IDN
import java.net.URLEncoder

/** Settings served by https://tgapp.selykvpn.xyz/api/v1/config. Built-in defaults are used offline. */
@Serializable
data class RemoteConfig(
    val allowedSubscriptionHosts: List<String> = listOf(
        "*.selykvpn.xyz",
        "selykvpn.xyz",
        "tgapp.selykvpn.xyz",
        "sub.selykvpn.xyz",
        "core.selykvpn.xyz",
        "panel.selykvpn.xyz",
        "panel.xamudhabibi.qzz.io",
    ),
    val subscriptionMirrors: List<String> = emptyList(),
    val telegramBotUrl: String = "https://t.me/selykvpn_bot?start=app",
    val renewUrl: String = "https://t.me/selykvpn_bot",
    val support: List<SupportContact> = listOf(
        SupportContact("telegram", "Telegram", "https://t.me/selykvpn_bot"),
    ),
    /** "Подписка" / "Наш бот" buttons on the main screen. */
    val links: List<AppLink> = listOf(
        AppLink("subscription", url = ""),
        AppLink("bot", url = "https://t.me/selykvpn_bot"),
    ),
    /** Current domains of the website and its API. */
    val site: SiteConfig = SiteConfig(),
    /** "Прокси для Telegram" under the links on the main screen; hidden while empty. */
    val telegramProxies: List<TelegramProxy> = emptyList(),
    /** Tabs above the server list. */
    val serverFilters: List<ServerFilter> = emptyList(),
    /** Operator white-list detection and the servers to switch to. */
    val whitelist: WhitelistConfig = WhitelistConfig(),
    /** Apps that work without VPN by default (unless the user turns this off in settings). */
    val defaultExcludedApps: List<String> = listOf(
        "ru.gosuslugi.goskey", "com.gosuslugi.portal",
        "ru.sberbankmobile", "ru.vtb24.mobilebanking",
        "ru.tinkoff.mobile.android",
        // Android Auto: a VPN gets in the way of its connection to the car.
        "com.google.android.projection.gearhead",
    ),
    val delayTestUrl: String = "https://www.gstatic.com/generate_204",
    val update: UpdateInfo? = null,
)

@Serializable
data class SupportContact(val type: String, val title: String, val url: String)

/** A proxy Telegram adds from its link (https://t.me/proxy, /socks, /webproxy or tg://). */
@Serializable
data class TelegramProxy(val title: String, val url: String, val flag: String? = null) {
    val valid: Boolean
        get() = title.isNotBlank() && url.trim().let { u -> TELEGRAM_LINK_PREFIXES.any { u.startsWith(it, ignoreCase = true) } }

    private companion object {
        val TELEGRAM_LINK_PREFIXES = listOf("https://t.me/", "https://telegram.me/", "tg://")
    }
}

@Serializable
data class ServerFilter(val emoji: String, val title: String)

/**
 * White lists: if only [allowedProbes] answer while [blockedProbes] don't, the operator lets
 * through only approved services. Servers are found by words in their names.
 */
@Serializable
data class WhitelistConfig(
    val blockedProbes: List<String> = listOf("https://www.google.com/generate_204", "https://www.gstatic.com/generate_204"),
    val allowedProbes: List<String> = listOf("https://vk.ru", "https://ya.ru"),
    val lteServer: List<String> = listOf("LTE"),
    val wifiServer: List<String> = listOf("Wi-Fi"),
    val bypassServer: List<String> = listOf("Все операторы"),
    /** While white lists are on, the ping button checks only servers with one of these words. */
    val pingServers: List<String> = listOf("Все операторы", "LTE"),
)

/** Host names without "https://". */
@Serializable
data class SiteConfig(
    val domain: String = "tgapp.selykvpn.xyz",
    /** For signing in by email (in development). */
    val apiDomain: String = "tgapp.selykvpn.xyz",
) {
    /** "Войти через сайт" → "Войти через браузер": the account page with the button that adds the key to the app. */
    val installUrl: String get() = "https://$domain/dashboard/install"

    /** "Продлить" / "Купить тариф": the site's payment page for the subscription [subscriptionId] (its short UUID). */
    fun renewalUrl(subscriptionId: String): String = "https://$domain/renewal?sub=" + URLEncoder.encode(subscriptionId, "UTF-8")

    /** "https://tgapp.selykvpn.xyz/" becomes "tgapp.selykvpn.xyz"; anything that is not a host name falls back to the default. */
    fun sanitized(): SiteConfig {
        val defaults = SiteConfig()
        return SiteConfig(
            domain = hostName(domain) ?: defaults.domain,
            apiDomain = hostName(apiDomain) ?: defaults.apiDomain,
        )
    }
}

private val HOST_NAME = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}(:\\d{1,5})?$")

private fun hostName(value: String): String? {
    val host = value.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .trimEnd('.').lowercase()
    if (host.isEmpty()) return null
    val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return null
    return ascii.takeIf { HOST_NAME.matches(it) }
}

/** A button with a link; known types ("website", "bot") get a localized title when [title] is not set. */
@Serializable
data class AppLink(val type: String, val title: String? = null, val url: String)

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    /** Per-ABI APKs ("arm64-v8a" -> url) and their SHA-256; [url]/[sha256] is the universal APK. */
    val abiUrls: Map<String, String> = emptyMap(),
    val abiSha256: Map<String, String> = emptyMap(),
    val sha256: String? = null,
    val changelog: String? = null,
)

class RemoteConfigRepository(context: Context, private val http: Http) {
    private val file = File(context.filesDir, "remote_config.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<RemoteConfig> = _state.asStateFlow()

    /** Time of the last successful refresh (the cached file's time after a restart). */
    @Volatile
    var fetchedAt: Long = if (file.exists()) file.lastModified() else 0L
        private set

    suspend fun refreshIfOlderThan(maxAgeMs: Long): Boolean =
        if (System.currentTimeMillis() - fetchedAt > maxAgeMs) refresh() else false

    private fun load(): RemoteConfig =
        runCatching { sanitize(AppJson.decodeFromString<RemoteConfig>(file.readText())) }.getOrDefault(RemoteConfig())

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.withClient { client ->
                val request = Request.Builder().url("${BuildConfig.API_BASE}/api/v1/config").build()
                client.newCall(request).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body.string()
                }
            }
            val config = sanitize(AppJson.decodeFromString<RemoteConfig>(body))
            file.writeText(AppJson.encodeToString(RemoteConfig.serializer(), config))
            fetchedAt = System.currentTimeMillis()
            _state.value = config
        }.isSuccess
    }

    /** A broken server response must never lock users out: empty lists fall back to defaults. */
    private fun sanitize(config: RemoteConfig): RemoteConfig {
        val defaults = RemoteConfig()
        return config.copy(
            allowedSubscriptionHosts = config.allowedSubscriptionHosts.ifEmpty { defaults.allowedSubscriptionHosts },
            support = config.support.ifEmpty { defaults.support },
            links = config.links.filter { it.url.isNotBlank() },
            telegramProxies = config.telegramProxies.filter { it.valid },
            site = config.site.sanitized(),
            defaultExcludedApps = config.defaultExcludedApps.map { it.trim() }.filter { it.isNotEmpty() },
            serverFilters = config.serverFilters.filter { it.emoji.isNotBlank() },
            telegramBotUrl = config.telegramBotUrl.ifBlank { defaults.telegramBotUrl },
            renewUrl = config.renewUrl.ifBlank { defaults.renewUrl },
            delayTestUrl = config.delayTestUrl.ifBlank { defaults.delayTestUrl },
        )
    }
}
