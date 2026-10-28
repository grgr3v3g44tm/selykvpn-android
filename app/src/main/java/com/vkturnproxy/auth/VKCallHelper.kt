package com.vkturnproxy.auth

import android.content.Context
import android.webkit.WebSettings
import com.google.gson.Gson
import com.vkturnproxy.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Helper for fast automated background HTTP token acquisition and VK call creation.
 * Pattern derived from SpaceNeuroX VkCallHashGenerator:
 * 1. If session cookies exist, tries direct headless `act=web_token` against login.vk.ru / login.vk.com (100ms, zero WebView).
 * 2. If web_token not available, falls back to HTTP OAuth redirect chain.
 * 3. Immediately executes calls.start and handles blocked accounts gracefully without hanging.
 */
object VKCallHelper {

    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun createCallFast(context: Context): FastCallResult = withContext(Dispatchers.IO) {
        val cookieHeader = VKCookieStore.getValidCookieHeader()
        if (cookieHeader.isNullOrBlank() || !VKCookieStore.hasValidLogin()) {
            AppLog.i(TAG, "Сессия VK не найдена или истекла, требуется вход в аккаунт")
            return@withContext FastCallResult.Error("Требуется вход в аккаунт VK", needsUi = true)
        }

        AppLog.i(TAG, "Запуск быстрого HTTP-создания звонка...")
        try {
            val token = obtainAccessTokenViaHttp(context, cookieHeader)
            if (token.isNullOrBlank()) {
                AppLog.w(TAG, "Быстрый HTTP-запрос токена вернул null — требуется интерактивное подтверждение прав в WebView")
                return@withContext FastCallResult.Error("Требуется подтвердить доступ в окне VK", needsUi = true)
            }

            AppLog.i(TAG, "OAuth токен успешно получен через HTTP, вызываем calls.start…")
            var callLink: String? = null
            var errorMsg: String? = null
            val latch = CountDownLatch(1)

            createCallWithToken(token) { link, err ->
                if (!link.isNullOrBlank()) {
                    callLink = link
                } else {
                    errorMsg = err
                    AppLog.w(TAG, "createCallWithToken завершился с ошибкой: $err")
                }
                latch.countDown()
            }

            val finished = latch.await(12, TimeUnit.SECONDS)
            if (!finished) {
                AppLog.w(TAG, "Таймаут ожидания ответа calls.start (12 секунд)")
                return@withContext FastCallResult.Error("Таймаут ответа от серверов VK (12 с)", needsUi = true)
            }

            if (!callLink.isNullOrBlank()) {
                AppLog.i(TAG, "Звонок успешно создан: $callLink")
                return@withContext FastCallResult.Success(callLink!!)
            }

            val finalErr = errorMsg ?: "Не удалось создать звонок VK"
            AppLog.e(TAG, "Ошибка создания звонка: $finalErr")
            val isBanned = finalErr.contains("заблокирован") || finalErr.contains("blocked") || finalErr.contains("banned")
            return@withContext FastCallResult.Error(
                message = finalErr,
                needsUi = !isBanned,
                isAccountBanned = isBanned
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Исключение при создании звонка", e)
            return@withContext FastCallResult.Error("Ошибка: ${e.message ?: "Сетевой сбой"}", needsUi = true)
        }
    }

    /**
     * Tries fast headless token acquisition:
     * 1. Direct POST https://login.vk.ru/?act=web_token (instant, no WebView)
     * 2. Direct POST https://login.vk.com/?act=web_token (fallback)
     * 3. Full HTTP redirect chain through oauth.vk.com/authorize
     */
    fun obtainAccessTokenViaHttp(context: Context, cookieHeader: String): String? {
        if (cookieHeader.isBlank()) return null
        val ua = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        // 1. Direct headless web_token POST
        val directToken = requestWebToken(cookieHeader, ua)
        if (!directToken.isNullOrBlank()) {
            AppLog.i(TAG, "Access token успешно получен через headless act=web_token!")
            return directToken
        }

        // 2. Fallback: follow HTTP OAuth redirect chain
        val redirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        var currentUrl = "https://oauth.vk.com/authorize" +
                "?client_id=$VK_CLIENT_ID" +
                "&display=mobile" +
                "&redirect_uri=$redirect" +
                "&response_type=token" +
                "&scope=messages,calls" +
                "&state=vkturn" +
                "&v=$API_VERSION"

        for (step in 0 until 10) {
            var conn: HttpURLConnection? = null
            try {
                AppLog.d(TAG, "HTTP Step $step: $currentUrl")
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                val code = conn.responseCode
                val location = conn.getHeaderField("Location") ?: ""
                AppLog.d(TAG, "HTTP Step $step status: $code, Location: $location")

                // 1. Check for access_token in Location header
                val locToken = extractAccessToken(location)
                if (locToken != null) {
                    AppLog.i(TAG, "Найден access_token в заголовке Location на шаге $step")
                    return locToken
                }

                // 2. Follow HTTP redirects (301, 302, 303, 307, 308)
                if (code in 300..399 && location.isNotBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(URL(currentUrl), location).toString()
                    }
                    continue
                }

                // 3. Inspect body for direct access token, location.href or grant_access
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

                val bodyToken = extractAccessToken(body)
                if (bodyToken != null) {
                    AppLog.i(TAG, "Найден access_token в теле ответа на шаге $step")
                    return bodyToken
                }

                val hrefMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)
                val href = hrefMatch?.groupValues?.getOrNull(1)
                val hrefToken = extractAccessToken(href)
                if (hrefToken != null) {
                    AppLog.i(TAG, "Найден access_token в location.href на шаге $step")
                    return hrefToken
                }

                val grantMatch = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""").find(body)
                val grantUrl = grantMatch?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                if (!grantUrl.isNullOrBlank()) {
                    AppLog.d(TAG, "Обнаружен grant_access URL на шаге $step: $grantUrl")
                    currentUrl = grantUrl
                    continue
                }

                AppLog.d(TAG, "HTTP-цепочка завершена на шаге $step (требуется UI-действие пользователя)")
                break
            } catch (e: Exception) {
                AppLog.w(TAG, "Ошибка HTTP-цепочки на шаге $step", e)
                break
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
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
                AppLog.d(TAG, "Запрос act=web_token к $url...")
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
                AppLog.d(TAG, "Ответ act=web_token ($host, code=$code): ${AppLog.sanitize(response)}")

                if (response.contains("\"access_token\"")) {
                    val map = Gson().fromJson(response, Map::class.java)
                    val data = map["data"] as? Map<*, *>
                    val token = data?.get("access_token") as? String
                    if (!token.isNullOrBlank()) {
                        return token
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Ошибка act=web_token к $host: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return null
    }
}
