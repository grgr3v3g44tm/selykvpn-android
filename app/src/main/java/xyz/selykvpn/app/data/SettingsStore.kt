package xyz.selykvpn.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import xyz.selykvpn.app.isTv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val selectedServer = stringPreferencesKey("selected_server")
        val excludedApps = stringSetPreferencesKey("excluded_apps")
        val notificationsAsked = booleanPreferencesKey("notifications_asked")
        val deviceId = stringPreferencesKey("device_id")
        val skippedUpdate = intPreferencesKey("skipped_update")
        val useRecommendedExclusions = booleanPreferencesKey("use_recommended_exclusions")
        val recommendedOverrides = stringSetPreferencesKey("recommended_overrides")
        val shareEnabled = booleanPreferencesKey("share_enabled")
        val serversOnLeft = booleanPreferencesKey("servers_on_left")
        val subscriptionUpdateHours = intPreferencesKey("subscription_update_hours")
        val shareHttpPort = intPreferencesKey("share_http_port")
        val shareSocksPort = intPreferencesKey("share_socks_port")
        val shareAuth = booleanPreferencesKey("share_auth")
        val shareUser = stringPreferencesKey("share_user")
        val sharePassword = stringPreferencesKey("share_password")
        val routingEnabled = booleanPreferencesKey("routing_enabled")
        val routingLanDirect = booleanPreferencesKey("routing_lan_direct")
        val routingRuDirect = booleanPreferencesKey("routing_ru_direct")
        val routingDirect = stringSetPreferencesKey("routing_direct")
        val routingProxy = stringSetPreferencesKey("routing_proxy")
        val routingBlock = stringSetPreferencesKey("routing_block")
        val whitelistDetect = booleanPreferencesKey("whitelist_detect")
        val whitelistAutoSwitched = stringPreferencesKey("whitelist_auto_switched")
        val connectionLog = booleanPreferencesKey("connection_log")
        val activeMode = stringPreferencesKey("active_mode")
        val themePalette = stringPreferencesKey("theme_palette")
        val freeAccessConfig = stringPreferencesKey("free_access_config")
    }

    private val data: Flow<Preferences> get() = context.dataStore.data

    val activeMode: Flow<String> = data.map { it[Keys.activeMode] ?: "regular" }
    val themePalette: Flow<String> = data.map { it[Keys.themePalette] ?: "selyk" }
    val freeAccessConfig: Flow<String?> = data.map { it[Keys.freeAccessConfig] }

    suspend fun setActiveMode(mode: String) {
        context.dataStore.edit { it[Keys.activeMode] = mode }
    }

    suspend fun setThemePalette(palette: String) {
        context.dataStore.edit { it[Keys.themePalette] = palette }
    }

    suspend fun setFreeAccessConfig(cfgJson: String?) {
        context.dataStore.edit {
            if (cfgJson != null) it[Keys.freeAccessConfig] = cfgJson else it.remove(Keys.freeAccessConfig)
        }
    }

    val selectedServer: Flow<String?> = data.map { it[Keys.selectedServer] }
    val excludedApps: Flow<Set<String>> = data.map { it[Keys.excludedApps].orEmpty() }

    /** Apps from the server's recommended list go around the VPN unless this is turned off. */
    val useRecommendedExclusions: Flow<Boolean> = data.map { it[Keys.useRecommendedExclusions] ?: true }

    /** Recommended apps the user put back into the VPN one by one. */
    val recommendedOverrides: Flow<Set<String>> = data.map { it[Keys.recommendedOverrides].orEmpty() }

    /** Share the VPN with other devices through a local HTTP/SOCKS5 proxy. */
    val shareEnabled: Flow<Boolean> = data.map { it[Keys.shareEnabled] == true }

    /** Two-pane layout (tablets, unfolded foldables): server list on the left, for left-handed use. */
    val serversOnLeft: Flow<Boolean> = data.map { it[Keys.serversOnLeft] == true }

    /**
     * How often the subscription is refreshed automatically, in hours.
     * [UPDATE_FROM_SUBSCRIPTION] uses the panel's profile-update-interval, [UPDATE_MANUAL] never.
     */
    val subscriptionUpdateHours: Flow<Int> = data.map { it[Keys.subscriptionUpdateHours] ?: UPDATE_FROM_SUBSCRIPTION }

    val share: Flow<ShareSettings> = data.map { p ->
        ShareSettings(
            enabled = p[Keys.shareEnabled] == true,
            httpPort = p[Keys.shareHttpPort] ?: ShareSettings.DEFAULT_HTTP_PORT,
            socksPort = p[Keys.shareSocksPort] ?: ShareSettings.DEFAULT_SOCKS_PORT,
            auth = p[Keys.shareAuth] == true,
            user = p[Keys.shareUser].orEmpty(),
            password = p[Keys.sharePassword].orEmpty(),
        )
    }

    val routing: Flow<RoutingSettings> = data.map { p ->
        RoutingSettings(
            enabled = p[Keys.routingEnabled] ?: true,
            lanDirect = p[Keys.routingLanDirect] ?: true,
            ruDirect = p[Keys.routingRuDirect] == true,
            direct = p[Keys.routingDirect].orEmpty(),
            proxy = p[Keys.routingProxy].orEmpty(),
            block = p[Keys.routingBlock].orEmpty(),
        )
    }

    /** Detect operator "white lists" and switch to bypass servers automatically. */
    /** Off by default on a TV: it sits on home Wi-Fi, where operators' white lists don't apply. */
    val whitelistDetectDefault: Boolean = !context.isTv()
    val whitelistDetect: Flow<Boolean> = data.map { it[Keys.whitelistDetect] ?: whitelistDetectDefault }

    /** Debug log of the apps' connections (Settings → Журнал подключений). */
    val connectionLog: Flow<Boolean> = data.map { it[Keys.connectionLog] == true }

    /** versionCode of the update the user chose to skip: its card stays hidden until a newer one. */
    val skippedUpdate: Flow<Int> = data.map { it[Keys.skippedUpdate] ?: 0 }

    suspend fun selectedServerNow(): String? = data.first()[Keys.selectedServer]
    suspend fun excludedAppsNow(): Set<String> = data.first()[Keys.excludedApps].orEmpty()
    suspend fun notificationsAsked(): Boolean = data.first()[Keys.notificationsAsked] == true

    suspend fun setSelectedServer(key: String?) = context.dataStore.edit {
        if (key == null) it.remove(Keys.selectedServer) else it[Keys.selectedServer] = key
    }

    suspend fun setExcludedApps(apps: Set<String>) = context.dataStore.edit { it[Keys.excludedApps] = apps }

    suspend fun setNotificationsAsked() = context.dataStore.edit { it[Keys.notificationsAsked] = true }

    suspend fun setSkippedUpdate(versionCode: Int) = context.dataStore.edit { it[Keys.skippedUpdate] = versionCode }

    suspend fun setUseRecommendedExclusions(enabled: Boolean) =
        context.dataStore.edit { it[Keys.useRecommendedExclusions] = enabled }

    suspend fun setShareEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.shareEnabled] = enabled }

    suspend fun setServersOnLeft(onLeft: Boolean) = context.dataStore.edit { it[Keys.serversOnLeft] = onLeft }

    suspend fun subscriptionUpdateHoursNow(): Int = subscriptionUpdateHours.first()

    suspend fun setSubscriptionUpdateHours(hours: Int) = context.dataStore.edit { it[Keys.subscriptionUpdateHours] = hours }

    suspend fun setSharePorts(httpPort: Int, socksPort: Int) = context.dataStore.edit {
        it[Keys.shareHttpPort] = httpPort
        it[Keys.shareSocksPort] = socksPort
    }

    /** Turns proxy authorization on, creating a login and password the first time. */
    suspend fun setShareAuth(enabled: Boolean) = context.dataStore.edit {
        it[Keys.shareAuth] = enabled
        if (enabled && it[Keys.shareUser].isNullOrEmpty()) {
            it[Keys.shareUser] = ShareSettings.newUser()
            it[Keys.sharePassword] = ShareSettings.newPassword()
        }
    }

    suspend fun resetShareCredentials() = context.dataStore.edit {
        it[Keys.shareUser] = ShareSettings.newUser()
        it[Keys.sharePassword] = ShareSettings.newPassword()
    }

    suspend fun setRoutingEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.routingEnabled] = enabled }
    suspend fun setRoutingLanDirect(enabled: Boolean) = context.dataStore.edit { it[Keys.routingLanDirect] = enabled }
    suspend fun setRoutingRuDirect(enabled: Boolean) = context.dataStore.edit { it[Keys.routingRuDirect] = enabled }

    /** Puts [entries] into one routing list, removing them from the other two. */
    suspend fun addRoutingRules(action: RouteAction, entries: Collection<String>) = context.dataStore.edit { p ->
        for (a in RouteAction.entries) {
            val key = routingKey(a)
            val current = p[key].orEmpty()
            p[key] = if (a == action) current + entries else current - entries.toSet()
        }
    }

    suspend fun removeRoutingRule(action: RouteAction, entry: String) = context.dataStore.edit { p ->
        val key = routingKey(action)
        p[key] = p[key].orEmpty() - entry
    }

    /** Replaces one entry with what it was edited into, in the same list (moving it out of the others). */
    suspend fun replaceRoutingRule(action: RouteAction, old: String, entries: Collection<String>) = context.dataStore.edit { p ->
        for (a in RouteAction.entries) {
            val key = routingKey(a)
            val current = p[key].orEmpty()
            p[key] = if (a == action) current - old + entries else current - entries.toSet()
        }
    }

    suspend fun clearRoutingRules(action: RouteAction) = context.dataStore.edit { it[routingKey(action)] = emptySet() }

    private fun routingKey(action: RouteAction) = when (action) {
        RouteAction.DIRECT -> Keys.routingDirect
        RouteAction.PROXY -> Keys.routingProxy
        RouteAction.BLOCK -> Keys.routingBlock
    }

    /**
     * Routing from a file: [replace] takes everything from it, otherwise its addresses are added
     * to the user's lists (moving out of the other lists, like adding them by hand).
     */
    suspend fun importRouting(routing: RoutingSettings, replace: Boolean) = context.dataStore.edit { p ->
        if (replace) {
            p[Keys.routingEnabled] = routing.enabled
            p[Keys.routingLanDirect] = routing.lanDirect
            p[Keys.routingRuDirect] = routing.ruDirect
            for (a in RouteAction.entries) p[routingKey(a)] = routing.rules(a)
        } else {
            val imported = RouteAction.entries.associateWith { routing.rules(it) }
            for (a in RouteAction.entries) {
                val elsewhere = imported.filterKeys { it != a }.values.flatten().toSet()
                p[routingKey(a)] = p[routingKey(a)].orEmpty() - elsewhere + imported.getValue(a)
            }
        }
    }

    /** Apps without VPN from a file: [replace] takes the whole setup, otherwise its apps are added. */
    suspend fun importApps(apps: AppsImport, replace: Boolean) = context.dataStore.edit { p ->
        if (replace) {
            p[Keys.excludedApps] = apps.excluded
            p[Keys.useRecommendedExclusions] = apps.useRecommended
            p[Keys.recommendedOverrides] = apps.returnedToVpn
        } else {
            p[Keys.excludedApps] = p[Keys.excludedApps].orEmpty() + apps.excluded
            p[Keys.recommendedOverrides] = p[Keys.recommendedOverrides].orEmpty() - apps.excluded
        }
    }

    suspend fun connectionLogNow(): Boolean = connectionLog.first()
    suspend fun setConnectionLog(enabled: Boolean) = context.dataStore.edit { it[Keys.connectionLog] = enabled }

    suspend fun whitelistDetectNow(): Boolean = whitelistDetect.first()
    suspend fun setWhitelistDetect(enabled: Boolean) = context.dataStore.edit { it[Keys.whitelistDetect] = enabled }

    /** Server key the app switched to because of white lists, to know what may be switched back. */
    suspend fun whitelistAutoSwitchedNow(): String? = data.first()[Keys.whitelistAutoSwitched]
    suspend fun setWhitelistAutoSwitched(key: String?) = context.dataStore.edit {
        if (key == null) it.remove(Keys.whitelistAutoSwitched) else it[Keys.whitelistAutoSwitched] = key
    }

    /** Includes or excludes one app, keeping manual choices and recommended-list overrides consistent. */
    suspend fun setAppExcluded(pkg: String, excluded: Boolean, recommended: Set<String>) = context.dataStore.edit { prefs ->
        val manual = prefs[Keys.excludedApps].orEmpty()
        val overrides = prefs[Keys.recommendedOverrides].orEmpty()
        val useRecommended = prefs[Keys.useRecommendedExclusions] ?: true
        val isRecommended = useRecommended && pkg in recommended
        if (excluded) {
            prefs[Keys.recommendedOverrides] = overrides - pkg
            if (!isRecommended) prefs[Keys.excludedApps] = manual + pkg
        } else {
            prefs[Keys.excludedApps] = manual - pkg
            if (isRecommended) prefs[Keys.recommendedOverrides] = overrides + pkg
        }
    }

    /** Stable per-device id sent as x-hwid, so the panel's device limit works when it is enabled. */
    @SuppressLint("HardwareIds")
    suspend fun deviceId(): String {
        data.first()[Keys.deviceId]?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val id = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.deviceId] = id }
        return id
    }
}

object Device {
    val model: String
        get() {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
        }
}

const val UPDATE_FROM_SUBSCRIPTION = 0
const val UPDATE_MANUAL = -1

data class ShareSettings(
    val enabled: Boolean,
    val httpPort: Int,
    val socksPort: Int,
    val auth: Boolean,
    val user: String,
    val password: String,
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_SOCKS_PORT = 1080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private val random = java.security.SecureRandom()
        private fun token(length: Int, alphabet: String) =
            buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

        fun newUser() = "selyk_" + token(8, "0123456789abcdef")
        fun newPassword() = token(14, "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789")
    }
}

enum class RouteAction { DIRECT, PROXY, BLOCK }

data class RoutingSettings(
    val enabled: Boolean = true,
    val lanDirect: Boolean = true,
    val ruDirect: Boolean = false,
    val direct: Set<String> = emptySet(),
    val proxy: Set<String> = emptySet(),
    val block: Set<String> = emptySet(),
) {
    fun rules(action: RouteAction): Set<String> = when (action) {
        RouteAction.DIRECT -> direct
        RouteAction.PROXY -> proxy
        RouteAction.BLOCK -> block
    }
}
