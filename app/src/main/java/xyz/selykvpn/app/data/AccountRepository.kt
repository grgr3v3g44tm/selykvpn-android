package xyz.selykvpn.app.data

import android.content.Context
import xyz.selykvpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Plan and expiry of the subscription owner, from https://tgapp.selykvpn.xyz/api/v1/subscription/status. */
@Serializable
data class AccountStatus(
    val found: Boolean = false,
    val planId: Int? = null,
    val planName: String? = null,
    /** Unix seconds; null when the plan never expires. */
    val expiresAt: Long? = null,
    val isFree: Boolean = false,
    val status: String? = null,
    val trafficUsed: Long? = null,
    val trafficLimit: Long? = null,
    val fetchedAt: Long = 0,
)

@Serializable
private data class StatusRequest(val url: String)

class AccountRepository(context: Context, private val http: Http) {
    private val file = File(context.filesDir, "account.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AccountStatus?> = _state.asStateFlow()

    private fun load(): AccountStatus? =
        runCatching { AppJson.decodeFromString<AccountStatus>(file.readText()) }.getOrNull()

    suspend fun refresh(subscriptionUrl: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        val cached = _state.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 10 * 60_000L) return@withContext
        runCatching {
            val body = AppJson.encodeToString(StatusRequest.serializer(), StatusRequest(subscriptionUrl))
                .toRequestBody("application/json".toMediaType())
            val text = http.withClient { client ->
                val request = Request.Builder().url("${BuildConfig.API_BASE}/api/v1/subscription/status").post(body).build()
                client.newCall(request).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body.string()
                }
            }
            val status = AppJson.decodeFromString<AccountStatus>(text).copy(fetchedAt = System.currentTimeMillis())
            file.writeText(AppJson.encodeToString(AccountStatus.serializer(), status))
            _state.value = status
        }
    }

    fun clear() {
        file.delete()
        _state.value = null
    }
}
