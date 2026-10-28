package com.vkturnproxy.auth

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "VKCallHelper"

/**
 * Helper for fast automated background HTTP token acquisition and VK call creation.
 * Pattern derived from SpaceNeuroX VkCallHashGenerator:
 * If valid session cookies (remixsid + p) exist, obtains access_token via HTTP redirect chain
 * without launching any WebView or UI dialog, and calls calls.start directly.
 */
object VKCallHelper {

    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun createCallFast(context: Context): String? = withContext(Dispatchers.IO) {
        val cookieHeader = VKCookieStore.getValidCookieHeader()
        if (cookieHeader.isNullOrBlank() || !VKCookieStore.hasValidLogin()) {
            Log.d(TAG, "No valid VK login cookie stored, fast path skipped")
            return@withContext null
        }

        try {
            val token = obtainAccessTokenViaHttp(context, cookieHeader)
            if (token.isNullOrBlank()) {
                Log.d(TAG, "Fast HTTP token acquisition returned null (requires UI confirmation)")
                return@withContext null
            }

            Log.i(TAG, "Fast HTTP token obtained successfully, creating VK call…")
            var callLink: String? = null
            val latch = CountDownLatch(1)

            createCallWithToken(token) { link, err ->
                if (!link.isNullOrBlank()) {
                    callLink = link
                } else {
                    Log.w(TAG, "createCallWithToken failed: $err")
                }
                latch.countDown()
            }

            latch.await(15, TimeUnit.SECONDS)
            if (!callLink.isNullOrBlank()) {
                Log.i(TAG, "Fast call creation succeeded: $callLink")
            }
            return@withContext callLink
        } catch (e: Exception) {
            Log.w(TAG, "Fast call creation exception", e)
            return@withContext null
        }
    }

    fun obtainAccessTokenViaHttp(context: Context, cookieHeader: String): String? {
        if (cookieHeader.isBlank()) return null
        val ua = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        val redirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        var currentUrl = "https://oauth.vk.com/authorize" +
                "?client_id=$VK_CLIENT_ID" +
                "&display=mobile" +
                "&redirect_uri=$redirect" +
                "&response_type=token" +
                "&scope=messages,calls" +
                "&state=vkturn" +
                "&v=$API_VERSION"

        for (step in 0 until 12) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                val code = conn.responseCode
                val location = conn.getHeaderField("Location") ?: ""

                // 1. Check for access_token in Location header
                val locToken = extractAccessToken(location)
                if (locToken != null) {
                    Log.d(TAG, "Found access_token in Location header on step $step")
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
                    Log.d(TAG, "Found access_token in response body on step $step")
                    return bodyToken
                }

                val hrefMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)
                val href = hrefMatch?.groupValues?.getOrNull(1)
                val hrefToken = extractAccessToken(href)
                if (hrefToken != null) {
                    Log.d(TAG, "Found access_token in location.href on step $step")
                    return hrefToken
                }

                val grantMatch = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""").find(body)
                val grantUrl = grantMatch?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                if (!grantUrl.isNullOrBlank()) {
                    currentUrl = grantUrl
                    continue
                }

                // If not redirected and no grant URL, we reached a page requiring user interaction
                break
            } catch (e: Exception) {
                Log.w(TAG, "obtainAccessTokenViaHttp failed on step $step: ${e.message}")
                break
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }
        return null
    }
}
