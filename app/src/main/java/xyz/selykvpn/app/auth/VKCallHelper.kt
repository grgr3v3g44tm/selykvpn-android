package xyz.selykvpn.app.auth

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "VKCallHelper"

sealed class FastCallResult {
    data class Success(val joinLink: String) : FastCallResult()
    data class Error(
        val message: String,
        val needsUi: Boolean = false,
        val isAccountBanned: Boolean = false
    ) : FastCallResult()
}

/**
 * Helper for fast automated background HTTP token acquisition and VK call creation:
 * 1. Tries direct headless `act=web_token` against login.vk.ru / login.vk.com.
 * 2. Falls back to HTTP OAuth redirect chain.
 * 3. Executes calls.start against api.vk.ru / api.vk.com / api.vk.me (v=5.276).
 */
object VKCallHelper {

    private const val VK_CLIENT_ID = "6287487"
    private const val OAUTH_API_VERSION = "5.199"
    private const val CALLS_API_VERSION = "5.276"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"
    private const val FAST_CALL_TIMEOUT_MS = 14_000L
    private const val HTTP_TIMEOUT_MS = 5_000L
    private const val MAX_OAUTH_STEPS = 6
    private const val MAX_VK_BODY_BYTES = 2L * 1024L * 1024L

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(8_000L, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    internal fun isTrustedVkHost(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        return h == "vk.com" || h.endsWith(".vk.com") ||
            h == "vk.ru" || h.endsWith(".vk.ru") ||
            h == "vk.me" || h.endsWith(".vk.me")
    }

    suspend fun createCallFast(context: Context): FastCallResult = withContext(Dispatchers.IO) {
        VKCookieStore.init(context)
        val cookieHeader = VKCookieStore.getValidCookieHeader()
        if (cookieHeader.isNullOrBlank() || !VKCookieStore.hasValidLogin()) {
            Log.i(TAG, "Сессия VK не найдена или отключена, требуется вход в аккаунт")
            return@withContext FastCallResult.Error("Требуется вход в аккаунт VK", needsUi = true)
        }

        Log.i(TAG, "Запуск быстрого HTTP-создания звонка...")
        withTimeoutOrNull(FAST_CALL_TIMEOUT_MS) {
            try {
                val token = obtainAccessTokenViaHttp(context, cookieHeader)
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "Быстрый HTTP-запрос токена вернул null — требуется подтверждение прав в WebView")
                    return@withTimeoutOrNull FastCallResult.Error("Требуется подтвердить доступ в окне VK", needsUi = true)
                }

                Log.i(TAG, "OAuth токен получен через HTTP, вызываем calls.start…")
                createCallSuspend(token)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка быстрого создания звонка", e)
                FastCallResult.Error("Ошибка сети при создании звонка: ${e.message}", needsUi = true)
            }
        } ?: FastCallResult.Error("Таймаут быстрого создания звонка VK", needsUi = true)
    }

    private suspend fun createCallSuspend(token: String): FastCallResult = suspendCancellableCoroutine { cont ->
        val call = createCallWithToken(token) { link, err, banned ->
            if (!cont.isActive) return@createCallWithToken
            val result = if (!link.isNullOrBlank()) {
                Log.i(TAG, "Быстрое создание звонка УСПЕШНО")
                FastCallResult.Success(link)
            } else {
                FastCallResult.Error(
                    message = err ?: "Не удалось создать звонок через VK API",
                    needsUi = !banned,
                    isAccountBanned = banned,
                )
            }
            cont.resume(result)
        }
        cont.invokeOnCancellation { call?.cancel() }
    }

    fun extractAccessToken(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val clean = rawUrl.trim('"', '\'', ' ')
        if (!clean.contains("access_token=")) return null

        val fragment = clean.substringAfter('#', "")
        if (fragment.contains("access_token=")) {
            for (param in fragment.split("&")) {
                if (param.startsWith("access_token=")) {
                    val token = param.removePrefix("access_token=").trim()
                    if (token.isNotEmpty()) return token
                }
            }
        }

        val tokenPart = clean.substringAfter("access_token=")
        val token = tokenPart.substringBefore('&')
            .substringBefore('#')
            .substringBefore(';')
            .substringBefore('"')
            .substringBefore('\'')
            .trim()
        return if (token.isNotEmpty()) token else null
    }

    internal fun obtainAccessTokenViaHttp(context: Context, cookieHeader: String): String? {
        if (cookieHeader.isBlank()) return null
        val userAgent = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        // 1. Direct headless web_token POST
        requestWebToken(cookieHeader, userAgent)?.let { return it }

        val cookieJar = linkedMapOf<String, String>()
        cookieHeader.split(";").forEach { part ->
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                cookieJar[trimmed.substring(0, eqIdx).trim()] = trimmed.substring(eqIdx + 1).trim()
            }
        }

        // 2. Fallback: HTTP OAuth redirect chain (strictly HTTPS + trusted VK hosts only)
        val redirect = java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
        var currentUrl = "https://oauth.vk.com/authorize" +
            "?client_id=$VK_CLIENT_ID" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages,calls" +
            "&state=vkturn" +
            "&v=$OAUTH_API_VERSION"

        for (step in 0 until MAX_OAUTH_STEPS) {
            try {
                val parsed = currentUrl.toHttpUrlOrNull() ?: break
                if (parsed.scheme != "https" || !isTrustedVkHost(parsed.host)) {
                    Log.w(TAG, "Blocked untrusted OAuth URL host=${parsed.host}")
                    break
                }
                val mergedCookie = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val request = Request.Builder()
                    .url(parsed)
                    .header("User-Agent", userAgent)
                    .header("Cookie", mergedCookie)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    resp.headers("Set-Cookie").forEach { rawCookie ->
                        val nv = rawCookie.substringBefore(";").trim()
                        val eq = nv.indexOf('=')
                        if (eq > 0) {
                            val k = nv.substring(0, eq).trim()
                            val v = nv.substring(eq + 1).trim()
                            if (v.isNotEmpty() && v != "DELETED") {
                                cookieJar[k] = v
                            }
                        }
                    }
                    val location = resp.header("Location") ?: ""
                    extractAccessToken(location)?.let { return it }

                    if (resp.isRedirect && location.isNotBlank()) {
                        val nextUrl = parsed.resolve(location) ?: break
                        if (nextUrl.scheme != "https" || !isTrustedVkHost(nextUrl.host)) {
                            Log.w(TAG, "Blocked untrusted OAuth redirect host=${nextUrl.host}")
                            break
                        }
                        currentUrl = nextUrl.toString()
                        continue
                    }

                    val body = readBoundedBody(resp)
                    extractAccessToken(body)?.let { return it }

                    val hrefMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)
                    extractAccessToken(hrefMatch?.groupValues?.getOrNull(1))?.let { return it }

                    val grantMatch = Regex("""(https://login\.vk\.(?:com|ru)/\?act=grant_access[^"'\s<]+)""").find(body)
                    val grantUrl = grantMatch?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                    if (!grantUrl.isNullOrBlank()) {
                        currentUrl = grantUrl
                        continue
                    }
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth HTTP step $step error: ${e.message}")
                break
            }
        }
        return null
    }

    private fun readBoundedBody(response: Response): String {
        val body = response.body ?: return ""
        val source = body.source()
        source.request(MAX_VK_BODY_BYTES + 1)
        val buffer = source.buffer
        val count = minOf(buffer.size, MAX_VK_BODY_BYTES)
        return buffer.readString(count, Charsets.UTF_8)
    }

    private fun requestWebToken(cookieHeader: String, ua: String): String? {
        val hosts = listOf("login.vk.ru", "login.vk.com")
        for (host in hosts) {
            try {
                val origin = "https://${if (host.endsWith(".ru")) "vk.ru" else "vk.com"}"
                val form = FormBody.Builder()
                    .add("version", "1")
                    .add("app_id", VK_CLIENT_ID)
                    .build()
                val req = Request.Builder()
                    .url("https://$host/?act=web_token")
                    .post(form)
                    .header("User-Agent", ua)
                    .header("Cookie", cookieHeader)
                    .header("Origin", origin)
                    .header("Referer", "$origin/")
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val response = readBoundedBody(resp)
                    if (response.contains("\"access_token\"")) {
                        val map = gson.fromJson(response, Map::class.java)
                        val data = map["data"] as? Map<*, *>
                        val token = data?.get("access_token") as? String
                        if (!token.isNullOrBlank()) {
                            return token
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "act=web_token $host error: ${e.message}")
            }
        }
        return null
    }

    fun interface CancelHandle {
        fun cancel()
    }

    fun createCallWithToken(
        token: String,
        onResult: (link: String?, error: String?, isBanned: Boolean) -> Unit
    ): CancelHandle {
        val hosts = listOf("api.vk.ru", "api.vk.com", "api.vk.me")
        val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
        val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val handle = CancelHandle {
            canceled.set(true)
            currentCall.get()?.cancel()
        }

        // Run via okhttp dispatcher thread instead of spawning raw Thread objects
        httpClient.dispatcher.executorService.execute {
            var lastError = "Не удалось связаться с серверами VK"
            var isBanned = false

            for (host in hosts) {
                if (canceled.get()) break
                try {
                    val form = FormBody.Builder()
                        .add("access_token", token)
                        .add("v", CALLS_API_VERSION)
                        .build()
                    val req = Request.Builder()
                        .url("https://$host/method/calls.start")
                        .post(form)
                        .header("User-Agent", "SelykVPN/2.0")
                        .header("Accept", "application/json")
                        .build()
                    val call = httpClient.newCall(req)
                    currentCall.set(call)
                    if (canceled.get()) {
                        call.cancel()
                        break
                    }
                    val responseStr = call.execute().use { resp -> readBoundedBody(resp) }
                    val map = gson.fromJson(responseStr, Map::class.java)
                    if (map != null && map.containsKey("error")) {
                        val errMap = map["error"] as? Map<*, *>
                        val msg = errMap?.get("error_msg") as? String ?: "VK error"
                        val errCode = (errMap?.get("error_code") as? Number)?.toInt() ?: 0
                        val banInfo = errMap?.get("ban_info") as? Map<*, *>
                        val memberName = banInfo?.get("member_name") as? String
                        val banned = errCode == 18 ||
                            (errCode == 5 && msg.contains("blocked", ignoreCase = true)) ||
                            banInfo != null ||
                            msg.contains("deactivated", ignoreCase = true) ||
                            msg.contains("banned", ignoreCase = true)

                        if (banned) {
                            isBanned = true
                            val namePart = if (!memberName.isNullOrBlank()) " «$memberName»" else ""
                            lastError = "Аккаунт VK$namePart заблокирован. Разблокируйте его или смените аккаунт."
                            break
                        }

                        lastError = "Ошибка VK $errCode: $msg"
                        if (errCode == 5) break
                        continue
                    }

                    val respMap = map?.get("response") as? Map<*, *>
                    val joinLink = respMap?.get("join_link") as? String
                    if (!joinLink.isNullOrBlank()) {
                        onResult(joinLink.trim(), null, false)
                        return@execute
                    }
                } catch (e: Exception) {
                    if (canceled.get()) break
                    lastError = "Сетевая ошибка ($host): ${e.message}"
                }
            }
            if (!canceled.get()) {
                onResult(null, lastError, isBanned)
            }
        }
        return handle
    }
}
