package xyz.selykvpn.app.data

import android.content.Context
import android.util.Log
import xyz.selykvpn.app.BuildConfig
import xyz.selykvpn.app.model.FreeAccessConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Plan and expiry of the subscription owner, from https://tgapp.selykvpn.xyz/api/v1/subscription/status. */
@Serializable
data class AccountStatus(
    val found: Boolean = false,
    val planId: String? = null,
    val planName: String? = null,
    /** Unix seconds; null when the plan never expires. */
    val expiresAt: Long? = null,
    val isFree: Boolean = false,
    val status: String? = null,
    val trafficUsed: Long? = null,
    val trafficLimit: Long? = null,
    val isWhitelist: Boolean = false,
    val vkturnUri: String? = null,
    val vkturnPassword: String? = null,
    val tunnelIp: String? = null,
    val vkturnDeviceLimitReached: Boolean = false,
    val vkturnSlotsLimit: Int? = null,
    val vkturnSlotsUsed: Int? = null,
    val fetchedAt: Long = 0,
)

@Serializable
private data class AccountStatusDto(
    val found: Boolean = false,
    val planId: JsonPrimitive? = null,
    val planName: String? = null,
    val expiresAt: JsonPrimitive? = null,
    val isFree: Boolean = false,
    val status: String? = null,
    val trafficUsed: Long? = null,
    val trafficLimit: Long? = null,
    val isWhitelist: Boolean = false,
    val vkturnUri: String? = null,
    val vkturnPassword: String? = null,
    val tunnelIp: String? = null,
    val vkturnDeviceLimitReached: Boolean = false,
    val vkturnSlotsLimit: Int? = null,
    val vkturnSlotsUsed: Int? = null,
)

@Serializable
private data class StatusRequest(
    val url: String,
    val hwid: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
)

/** Determines whether "Свободный доступ" is unlocked for the imported subscription / account tariff. */
object TariffAccess {
    fun isFreeAccessAllowed(
        hasSubscription: Boolean,
        account: AccountStatus?,
        freeAccessConfig: FreeAccessConfig,
    ): Boolean {
        if (!hasSubscription) return true
        if (account != null && account.found) {
            return account.isWhitelist && !account.vkturnDeviceLimitReached
        }
        return freeAccessConfig.hasGatewayCredentials()
    }
}

class AccountRepository(context: Context, private val http: Http) {
    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "account.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AccountStatus?> = _state.asStateFlow()

    private fun load(): AccountStatus? =
        runCatching { AppJson.decodeFromString<AccountStatus>(file.readText()) }.getOrNull()

    suspend fun refresh(subscriptionUrl: String, force: Boolean = false): AccountStatus? = withContext(Dispatchers.IO) {
        if (!subscriptionUrl.startsWith("http", ignoreCase = true)) return@withContext null
        val cached = _state.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 10 * 60_000L) {
            return@withContext cached
        }
        checkStatus(subscriptionUrl)?.also { save(it) }
    }

    /** Queries `/api/v1/subscription/status` without mutating persisted state or UI flows. */
    suspend fun checkStatus(subscriptionUrl: String): AccountStatus? = withContext(Dispatchers.IO) {
        if (!subscriptionUrl.startsWith("http", ignoreCase = true)) return@withContext null
        runCatching {
            val hwid = FreeAccessConfig.resolveStableDeviceId(appContext)
            val reqBody = StatusRequest(
                url = subscriptionUrl,
                hwid = hwid,
                deviceName = Device.model,
                platform = "android",
                appVersion = BuildConfig.VERSION_NAME,
            )
            val body = AppJson.encodeToString(StatusRequest.serializer(), reqBody)
                .toRequestBody("application/json".toMediaType())
            val text = http.withClient { client ->
                val request = Request.Builder()
                    .url("${BuildConfig.API_BASE}/api/v1/subscription/status")
                    .header("x-hwid", hwid)
                    .post(body)
                    .build()
                client.newCall(request).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body.string()
                }
            }
            parseResponse(text, System.currentTimeMillis())
        }.onFailure { err ->
            Log.w("AccountRepository", "Failed to check subscription status: ${err.message}")
        }.getOrNull()
    }

    fun save(status: AccountStatus) {
        file.writeText(AppJson.encodeToString(AccountStatus.serializer(), status))
        _state.value = status
    }

    fun clear() {
        file.delete()
        _state.value = null
    }

    companion object {
        fun parseResponse(jsonText: String, fetchedAt: Long = System.currentTimeMillis()): AccountStatus {
            val dto = AppJson.decodeFromString<AccountStatusDto>(jsonText)
            return AccountStatus(
                found = dto.found,
                planId = dto.planId?.contentOrNull,
                planName = dto.planName,
                expiresAt = parseExpiresAtSeconds(dto.expiresAt),
                isFree = dto.isFree,
                status = dto.status,
                trafficUsed = dto.trafficUsed,
                trafficLimit = dto.trafficLimit,
                isWhitelist = dto.isWhitelist,
                vkturnUri = dto.vkturnUri,
                vkturnPassword = dto.vkturnPassword,
                tunnelIp = dto.tunnelIp,
                vkturnDeviceLimitReached = dto.vkturnDeviceLimitReached,
                vkturnSlotsLimit = dto.vkturnSlotsLimit,
                vkturnSlotsUsed = dto.vkturnSlotsUsed,
                fetchedAt = fetchedAt,
            )
        }

        fun parseExpiresAtSeconds(raw: JsonPrimitive?): Long? {
            if (raw == null) return null
            raw.longOrNull?.let { num ->
                return if (num > 10_000_000_000L) num / 1000L else num
            }
            val str = raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            str.toLongOrNull()?.let { num ->
                return if (num > 10_000_000_000L) num / 1000L else num
            }
            val normalized = str
                .replace(Regex("""\.\d+(?=(Z|[+-]\d{2}:?\d{2})$)"""), "")
                .replace("Z", "+0000")
                .replace(Regex("""([+-]\d{2}):(\d{2})$"""), "$1$2")
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")
            for (pattern in patterns) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }.parse(normalized)
                }.getOrNull()
                if (parsed != null) return parsed.time / 1000L
            }
            return null
        }
    }
}
