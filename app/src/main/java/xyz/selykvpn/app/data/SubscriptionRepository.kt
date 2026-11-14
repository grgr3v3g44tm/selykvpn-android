package xyz.selykvpn.app.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.Headers
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed class SubscriptionError(message: String) : Exception(message) {
    class Foreign : SubscriptionError("foreign subscription host")
    class NotFound : SubscriptionError("subscription not found")
    class BadFormat : SubscriptionError("unsupported subscription format")
    class NoServers : SubscriptionError("subscription has no servers")
    class DeviceLimit : SubscriptionError("device limit reached")
    class FreeAccessDeviceLimit : SubscriptionError("free access device limit reached")
    class Expired : SubscriptionError("subscription expired")
    class Unsupported : SubscriptionError("client or protocol unsupported")
    class Network(cause: Throwable) : SubscriptionError(cause.message ?: "network error")
}

class SubscriptionRepository(
    private val context: Context,
    private val http: Http,
    private val remoteConfig: RemoteConfigRepository,
    private val settings: SettingsStore,
) {
    private val infoFile = File(context.filesDir, "subscription.json")
    private val configsFile = File(context.filesDir, "subscription_configs.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(loadInfo())
    val state: StateFlow<SubscriptionInfo?> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    @Volatile
    private var configs: List<String>? = null

    private fun loadInfo(): SubscriptionInfo? =
        runCatching {
            val raw = SecureBox.readEncryptedOrMigrate(infoFile) ?: return@runCatching null
            AppJson.decodeFromString<SubscriptionInfo>(raw)
        }.getOrNull()

    /** Downloads and stores the subscription for a new key. */
    suspend fun import(key: KeyParser.Key): SubscriptionInfo = mutex.withLock {
        val downloaded = if (!key.directConfigs.isNullOrEmpty()) {
            Downloaded(
                configs = key.directConfigs,
                headers = Headers.Builder()
                    .add("profile-title", "Selyk VPN")
                    .build()
            )
        } else {
            download(key)
        }
        save(key, downloaded).also { saved ->
            saved.selectable.firstOrNull()?.key?.let { settings.setSelectedServer(it) }
        }
    }

    /** Re-downloads the current subscription; keeps the old copy on failure. */
    suspend fun refresh(): SubscriptionInfo? = mutex.withLock {
        val current = _state.value ?: return@withLock null
        if (current.url.startsWith("vless://", ignoreCase = true)) return@withLock current
        val key = (KeyParser.parse(current.url, remoteConfig.state.value.allowedSubscriptionHosts) as? KeyParser.Result.Ok)?.key
            ?: return@withLock null
        _refreshing.value = true
        try {
            val downloaded = if (!key.directConfigs.isNullOrEmpty()) {
                Downloaded(configs = key.directConfigs, headers = Headers.Builder().add("profile-title", "Selyk VPN").build())
            } else {
                download(key)
            }
            save(key, downloaded)
        } finally {
            _refreshing.value = false
        }
    }

    /** [updateHours]: the setting from SettingsStore (hours, or UPDATE_FROM_SUBSCRIPTION / UPDATE_MANUAL). */
    fun isStale(updateHours: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (updateHours == UPDATE_MANUAL) return false
        val info = _state.value ?: return false
        val hours = (if (updateHours == UPDATE_FROM_SUBSCRIPTION) info.updateIntervalHours ?: 6 else updateHours).coerceIn(1, 48)
        return now - info.fetchedAt > hours * 3_600_000L
    }

    /** Raw Xray config of a server, as served by the panel. */
    suspend fun configFor(serverKey: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val info = _state.value ?: return@withContext null
            val index = info.servers.indexOfFirst { it.key == serverKey }.takeIf { it >= 0 } ?: return@withContext null
            loadConfigs().getOrNull(index)
        }
    }

    /** The selected server, falling back to the first selectable one. */
    suspend fun selectedServer(): ServerEntry? {
        val info = _state.value ?: return null
        val saved = settings.selectedServerNow()
        return info.selectable.firstOrNull { it.key == saved } ?: info.selectable.firstOrNull()
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            infoFile.delete()
            configsFile.delete()
        }
        configs = null
        _state.value = null
        settings.setSelectedServer(null)
    }

    private fun loadConfigs(): List<String> {
        configs?.let { return it }
        val list = runCatching {
            val raw = SecureBox.readEncryptedOrMigrate(configsFile) ?: return@runCatching emptyList()
            AppJson.parseToJsonElement(raw).jsonArray.map { it.toString() }
        }.getOrDefault(emptyList())
        configs = list
        return list
    }

    private class Downloaded(val configs: List<JsonObject>, val headers: Headers)

    private suspend fun save(key: KeyParser.Key, downloaded: Downloaded): SubscriptionInfo = withContext(Dispatchers.IO) {
        val entries = SubscriptionParser.entries(downloaded.configs)
        if (entries.none { it.kind != ServerKind.SEPARATOR }) {
            val allRemarks = entries.map { it.remarks.lowercase() }
            val dummyText = allRemarks.joinToString(" ")
            when {
                dummyText.contains("лимит") || dummyText.contains("устройств") || dummyText.contains("limit") ->
                    throw SubscriptionError.DeviceLimit()
                dummyText.contains("закончилась") || dummyText.contains("истекла") || dummyText.contains("expired") ->
                    throw SubscriptionError.Expired()
                dummyText.contains("не поддерживается") || dummyText.contains("unsupported") ->
                    throw SubscriptionError.Unsupported()
                else -> throw SubscriptionError.NoServers()
            }
        }
        val headers = downloaded.headers
        val userInfo = SubscriptionParser.parseUserInfo(headers["subscription-userinfo"])
        val info = SubscriptionInfo(
            url = key.url,
            title = SubscriptionParser.decodeHeader(headers["profile-title"]),
            announce = SubscriptionParser.decodeHeader(headers["announce"]),
            supportUrl = headers["support-url"]?.trim()?.takeIf { it.startsWith("http") },
            updateIntervalHours = headers["profile-update-interval"]?.trim()?.toIntOrNull(),
            upload = userInfo["upload"],
            download = userInfo["download"],
            total = userInfo["total"]?.takeIf { it > 0 },
            expire = userInfo["expire"]?.takeIf { it > 0 },
            fetchedAt = System.currentTimeMillis(),
            servers = entries,
        )
        SecureBox.writeEncryptedAtomically(configsFile, JsonArray(downloaded.configs).toString())
        SecureBox.writeEncryptedAtomically(infoFile, AppJson.encodeToString(SubscriptionInfo.serializer(), info))
        configs = downloaded.configs.map { it.toString() }
        _state.value = info
        info
    }

    /**
     * Tries the key's own host first and, if it is slow or unreachable, the mirror domains from
     * the remote config with the same path. The first valid response wins.
     */
    private suspend fun download(key: KeyParser.Key): Downloaded {
        val config = remoteConfig.state.value
        val allowed = KeyParser.mergedAllowedHosts(config.allowedSubscriptionHosts)
        val candidates = buildList {
            add(key.url + "/json")
            add(key.url)
            config.subscriptionMirrors.forEach { mirror ->
                val base = mirror.trim().trimEnd('/')
                if (base.startsWith("https://", ignoreCase = true)) {
                    val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
                    if (host.isNotEmpty() && host != key.host && KeyParser.hostAllowed(host, allowed)) {
                        add(base + key.path + "/json")
                        add(base + key.path)
                    }
                }
            }
        }.distinct()
        val hwid = settings.deviceId()
        return raceStaggered(candidates.map { url -> suspend { fetch(url, hwid, allowed) } }, staggerMs = 3_000)
    }

    private suspend fun fetch(url: String, hwid: String, allowed: List<String>): Downloaded = withContext(Dispatchers.IO) {
        http.withClient { client ->
            val safeClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            var currentUrl = url
            var hops = 0
            while (true) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("x-hwid", hwid)
                    .header("x-device-os", "Android")
                    .header("x-ver-os", Build.VERSION.RELEASE.orEmpty())
                    .header("x-device-model", Device.model)
                    .build()
                if (request.url.scheme != "https" || !KeyParser.hostAllowed(request.url.host, allowed)) {
                    throw SubscriptionError.Foreign()
                }
                safeClient.newCall(request).await().use { response ->
                    if (response.isRedirect && hops < 4) {
                        val loc = response.header("Location") ?: throw IOException("Empty redirect")
                        val resolved = request.url.resolve(loc) ?: throw SubscriptionError.Foreign()
                        if (resolved.scheme != "https" || !KeyParser.hostAllowed(resolved.host, allowed)) {
                            throw SubscriptionError.Foreign()
                        }
                        currentUrl = resolved.toString()
                        hops++
                        return@use
                    }
                    if (response.code == 404) throw SubscriptionError.NotFound()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val len = response.body.contentLength()
                    if (len > MAX_SUB_BYTES) throw IOException("Subscription too large")
                    val bodyBytes = response.body.source().let { src ->
                        if (src.request(MAX_SUB_BYTES + 1)) throw IOException("Subscription exceeds size limit")
                        src.readByteArray()
                    }
                    val body = bodyBytes.toString(Charsets.UTF_8)
                    val parsed = runCatching { SubscriptionParser.parseConfigs(body) }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: runCatching { SubscriptionParser.parseVlessList(body) }.getOrNull()
                    if (parsed.isNullOrEmpty()) throw SubscriptionError.BadFormat()
                    return@withClient Downloaded(parsed, response.headers)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Too many redirects")
        }
    }

    private companion object {
        const val MAX_SUB_BYTES = 10L * 1024L * 1024L
    }
}

/**
 * Starts the tasks one after another with a delay (a failed task starts the next one at once)
 * and returns the first successful result. Throws the most meaningful error if all fail.
 */
suspend fun <T> raceStaggered(tasks: List<suspend () -> T>, staggerMs: Long): T = coroutineScope {
    val winner = CompletableDeferred<T>()
    val errors = mutableListOf<Throwable>()
    val nextStart = List(tasks.size) { CompletableDeferred<Unit>() }
    val jobs = tasks.mapIndexed { index, task ->
        launch {
            if (index > 0) {
                launch { delay(staggerMs * index); nextStart[index].complete(Unit) }
                nextStart[index].await()
            }
            try {
                winner.complete(task())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                nextStart.getOrNull(index + 1)?.complete(Unit)
                val all = synchronized(errors) {
                    errors += e
                    errors.size == tasks.size
                }
                if (all) winner.completeExceptionally(pickError(errors))
            }
        }
    }
    try {
        winner.await()
    } finally {
        jobs.forEach { it.cancel() }
    }
}

private fun pickError(errors: List<Throwable>): Throwable =
    errors.firstOrNull { it is SubscriptionError.DeviceLimit }
        ?: errors.firstOrNull { it is SubscriptionError.Expired }
        ?: errors.firstOrNull { it is SubscriptionError.Unsupported }
        ?: errors.firstOrNull { it is SubscriptionError.NotFound }
        ?: errors.firstOrNull { it is SubscriptionError.Foreign }
        ?: errors.firstOrNull { it is SubscriptionError.BadFormat }
        ?: errors.firstOrNull { it is SubscriptionError.NoServers }
        ?: errors.firstOrNull { it is SubscriptionError }
        ?: SubscriptionError.Network(errors.last())
