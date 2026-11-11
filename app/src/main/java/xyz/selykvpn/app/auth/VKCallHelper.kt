package xyz.selykvpn.app.auth

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.google.gson.Gson
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
 */
object VKCallHelper {

    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun createCallFast(context: Context): FastCallResult = withContext(Dispatchers.IO) {
        val cookieHeader = VKCookieStore.getValidCookieHeader()
        if (cookieHeader.isNullOrBlank() || !VKCookieStore.hasValidLogin()) {
            Log.i(TAG, "Сессия VK не найдена или истекла, требуется вход в аккаунт")
            return@withContext FastCallResult.Error("Требуется вход в аккаунт VK", needsUi = true)
        }

        Log.i(TAG, "Запуск быстрого HTTP-создания звонка...")
        try {
            val token = obtainAccessTokenViaHttp(context, cookieHeader)
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Быстрый HTTP-запрос токена вернул null — требуется интерактивное подтверждение прав в WebView")
                return@withContext FastCallResult.Error("Требуется подтвердить доступ в окне VK", needsUi = true)
            }

            Log.i(TAG, "OAuth токен успешно получен через HTTP, вызываем calls.start…")
            var callLink: String? = null
            var errorMsg: String? = null
            val latch = CountDownLatch(1)

            createCallWithToken(token) { link, err ->
                if (!link.isNullOrBlank()) {
                    callLink = link
                } else {
                    errorMsg = err
                }
                latch.countDown()
            }

            val finished = latch.await(10, TimeUnit.SECONDS)
            if (!finished) {
                return@withContext FastCallResult.Error("Таймаут вызова calls.start")
            }

            val finalLink = callLink
            if (!finalLink.isNullOrBlank()) {
                Log.i(TAG, "Быстрое создание звонка УСПЕШНО")
                FastCallResult.Success(finalLink)
            } else {
                val isBanned = errorMsg?.contains("User authorization failed: user is deactivated") == true ||
                        errorMsg?.contains("account is blocked") == true
                FastCallResult.Error(errorMsg ?: "Не удалось создать звонок через VK API", isAccountBanned = isBanned)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка быстрого создания звонка", e)
            FastCallResult.Error("Ошибка сети при создании звонка: ${e.message}")
        }
    }

    private fun obtainAccessTokenViaHttp(context: Context, cookieHeader: String): String? {
        val userAgent = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        val oauthUrl = "https://oauth.vk.com/authorize?client_id=$VK_CLIENT_ID&display=mobile&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}&response_type=token&scope=messages,calls&v=$API_VERSION"

        var currentUrl = oauthUrl
        var redirects = 0
        val maxRedirects = 8

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                val code = conn.responseCode
                val location = conn.getHeaderField("Location")

                if (code in 300..399 && !location.isNullOrBlank()) {
                    val target = if (location.startsWith("/")) {
                        "${url.protocol}://${url.host}$location"
                    } else {
                        location
                    }

                    if (target.contains("blank.html") && target.contains("access_token=")) {
                        val token = target.substringAfter("access_token=").substringBefore("&")
                        if (token.isNotBlank()) return token
                    }
                    currentUrl = target
                    redirects++
                } else {
                    break
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    fun createCallWithToken(token: String, onResult: (link: String?, error: String?) -> Unit) {
        Thread {
            try {
                val apiUrl = "https://api.vk.ru/method/calls.start"
                val postData = "access_token=${URLEncoder.encode(token, "UTF-8")}&v=$API_VERSION"
                val postBytes = postData.toByteArray(Charsets.UTF_8)

                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Content-Length", postBytes.size.toString())

                conn.outputStream.use { it.write(postBytes) }

                val code = conn.responseCode
                val responseStr = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                conn.disconnect()

                val json = Gson().fromJson(responseStr, com.google.gson.JsonObject::class.java)
                if (json != null && json.has("response")) {
                    val respObj = json.getAsJsonObject("response")
                    val joinUrl = respObj.get("join_link")?.asString
                    if (!joinUrl.isNullOrBlank()) {
                        onResult(joinUrl, null)
                        return@Thread
                    }
                }

                if (json != null && json.has("error")) {
                    val errObj = json.getAsJsonObject("error")
                    val msg = errObj.get("error_msg")?.asString ?: "Unknown error"
                    onResult(null, msg)
                    return@Thread
                }
                onResult(null, "Не удалось разобрать ответ VK API: $responseStr")
            } catch (e: Exception) {
                onResult(null, e.message ?: "Network error")
            }
        }.start()
    }
}
