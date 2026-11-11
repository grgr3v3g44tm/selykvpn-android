package xyz.selykvpn.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebSettings
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    suspend fun createCallFast(context: Context): FastCallResult = withContext(Dispatchers.IO) {
        VKCookieStore.init(context)
        val cookieHeader = VKCookieStore.getValidCookieHeader()
        if (cookieHeader.isNullOrBlank() || !VKCookieStore.hasValidLogin()) {
            Log.i(TAG, "Сессия VK не найдена или отключена, требуется вход в аккаунт")
            return@withContext FastCallResult.Error("Требуется вход в аккаунт VK", needsUi = true)
        }

        Log.i(TAG, "Запуск быстрого HTTP-создания звонка...")
        try {
            val token = obtainAccessTokenViaHttp(context, cookieHeader)
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Быстрый HTTP-запрос токена вернул null — требуется подтверждение прав в WebView")
                return@withContext FastCallResult.Error("Требуется подтвердить доступ в окне VK", needsUi = true)
            }

            Log.i(TAG, "OAuth токен получен через HTTP, вызываем calls.start…")
            var callLink: String? = null
            var errorMsg: String? = null
            var isBanned = false
            val latch = CountDownLatch(1)

            createCallWithToken(token) { link, err, banned ->
                callLink = link
                errorMsg = err
                isBanned = banned
                latch.countDown()
            }

            val finished = latch.await(15, TimeUnit.SECONDS)
            if (!finished) {
                return@withContext FastCallResult.Error("Таймаут вызова calls.start", needsUi = true)
            }

            val finalLink = callLink
            if (!finalLink.isNullOrBlank()) {
                Log.i(TAG, "Быстрое создание звонка УСПЕШНО")
                FastCallResult.Success(finalLink)
            } else {
                FastCallResult.Error(
                    message = errorMsg ?: "Не удалось создать звонок через VK API",
                    needsUi = !isBanned,
                    isAccountBanned = isBanned
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка быстрого создания звонка", e)
            FastCallResult.Error("Ошибка сети при создании звонка: ${e.message}", needsUi = true)
        }
    }

    fun extractAccessToken(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val clean = rawUrl.trim('"', '\'', ' ')
        if (!clean.contains("access_token=")) return null

        try {
            val uri = Uri.parse(clean)
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank() && fragment.contains("access_token=")) {
                for (param in fragment.split("&")) {
                    if (param.startsWith("access_token=")) {
                        val token = param.removePrefix("access_token=").trim()
                        if (token.isNotEmpty()) return token
                    }
                }
            }
        } catch (_: Exception) {}

        val tokenPart = clean.substringAfter("access_token=")
        val token = tokenPart.substringBefore('&')
            .substringBefore('#')
            .substringBefore(';')
            .substringBefore('"')
            .substringBefore('\'')
            .trim()
        return if (token.isNotEmpty()) token else null
    }

    private fun obtainAccessTokenViaHttp(context: Context, cookieHeader: String): String? {
        if (cookieHeader.isBlank()) return null
        val userAgent = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        // 1. Direct headless web_token POST
        requestWebToken(cookieHeader, userAgent)?.let { return it }

        // 2. Fallback: HTTP OAuth redirect chain
        val redirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        var currentUrl = "https://oauth.vk.com/authorize" +
            "?client_id=$VK_CLIENT_ID" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages,calls" +
            "&state=vkturn" +
            "&v=$OAUTH_API_VERSION"

        for (step in 0 until 10) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                val code = conn.responseCode
                val location = conn.getHeaderField("Location") ?: ""

                extractAccessToken(location)?.let { return it }

                if (code in 300..399 && location.isNotBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(URL(currentUrl), location).toString()
                    }
                    continue
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

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
            } catch (e: Exception) {
                Log.w(TAG, "OAuth HTTP step $step error: ${e.message}")
                break
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    private fun requestWebToken(cookieHeader: String, ua: String): String? {
        val hosts = listOf("login.vk.ru", "login.vk.com")
        for (host in hosts) {
            var conn: HttpURLConnection? = null
            try {
                val origin = "https://${if (host.endsWith(".ru")) "vk.ru" else "vk.com"}"
                val url = "https://$host/?act=web_token"
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("Origin", origin)
                conn.setRequestProperty("Referer", "$origin/")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")

                val postData = "version=1&app_id=$VK_CLIENT_ID".toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(postData) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (response.contains("\"access_token\"")) {
                    val map = Gson().fromJson(response, Map::class.java)
                    val data = map["data"] as? Map<*, *>
                    val token = data?.get("access_token") as? String
                    if (!token.isNullOrBlank()) {
                        return token
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "act=web_token $host error: ${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    fun createCallWithToken(
        token: String,
        onResult: (link: String?, error: String?, isBanned: Boolean) -> Unit
    ) {
        Thread {
            val hosts = listOf("api.vk.ru", "api.vk.com", "api.vk.me")
            var lastError = "Не удалось связаться с серверами VK"
            var isBanned = false

            for (host in hosts) {
                var conn: HttpURLConnection? = null
                try {
                    val endpoint = "https://$host/method/calls.start"
                    val postData = "access_token=${URLEncoder.encode(token, "UTF-8")}&v=$CALLS_API_VERSION"
                    val postBytes = postData.toByteArray(Charsets.UTF_8)

                    conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.setRequestProperty("User-Agent", "SelykVPN/2.0")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("Content-Length", postBytes.size.toString())

                    conn.outputStream.use { it.write(postBytes) }

                    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                    val responseStr = BufferedReader(InputStreamReader(stream)).use { it.readText() }

                    val map = Gson().fromJson(responseStr, Map::class.java)
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
                        return@Thread
                    }
                } catch (e: Exception) {
                    lastError = "Сетевая ошибка ($host): ${e.message}"
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }

            onResult(null, lastError, isBanned)
        }.start()
    }
}
