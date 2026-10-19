package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VKCallCreator"
private const val VK_OAUTH_CLIENT_ID = "6287487"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VKCallCreatorDialog(
    onDismiss: () -> Unit,
    onCallCreated: (joinLink: String) -> Unit
) {
    var statusText by remember { mutableStateOf("Подтвердите аккаунт для создания звонка…") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var isTokenHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 1. Fast path: check if we already have a valid stored token
    LaunchedEffect(Unit) {
        val cachedToken = VKCookieStore.getToken()
        if (!cachedToken.isNullOrBlank()) {
            isLoading = true
            statusText = "Создаем звонок через VK API…"
            createCallWithToken(cachedToken) { link, err ->
                if (link != null) {
                    onCallCreated(link)
                    onDismiss()
                } else {
                    Log.d(TAG, "Cached token failed ($err), proceeding with web auth")
                    VKCookieStore.clearToken()
                    isLoading = false
                    statusText = "Войдите или нажмите «Продолжить как…»"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewInstance?.apply {
                    stopLoading()
                    clearHistory()
                    loadUrl("about:blank")
                    onPause()
                    destroy()
                }
            } catch (_: Exception) {}
            System.gc()
        }
    }

    fun handleTokenFound(token: String, webView: WebView?) {
        if (isTokenHandled) return
        isTokenHandled = true
        isLoading = true
        statusText = "Токен получен, вызываем calls.start…"

        try {
            webView?.stopLoading()
        } catch (_: Exception) {}

        VKCookieStore.saveToken(token)
        harvestCookies(CookieManager.getInstance())

        scope.launch {
            createCallWithToken(token) { link, err ->
                if (link != null) {
                    onCallCreated(link)
                    onDismiss()
                } else {
                    isLoading = false
                    isTokenHandled = false
                    errorMessage = err ?: "Ошибка создания звонка"
                }
            }
        }
    }

    fun inspectUrl(url: String?, webView: WebView?) {
        if (isTokenHandled || url.isNullOrBlank()) return

        val token = extractAccessToken(url)
        if (token != null) {
            handleTokenFound(token, webView)
            return
        }

        // Only intercept if this is truly the final OAuth redirect page (/blank.html)
        if (isOAuthBlankPage(url)) {
            isLoading = true
            statusText = "Авторизация подтверждена, создание звонка…"
            webView?.evaluateJavascript("window.location.href") { jsUrl ->
                val jsToken = extractAccessToken(jsUrl)
                if (jsToken != null) {
                    handleTokenFound(jsToken, webView)
                } else {
                    webView.evaluateJavascript("window.location.hash") { jsHash ->
                        val hashToken = extractAccessToken(jsHash)
                        if (hashToken != null) {
                            handleTokenFound(hashToken, webView)
                        }
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        VkTurnTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                color = BgDark
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    "Создание звонка VK",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    statusText,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (canGoBack && webViewInstance?.canGoBack() == true) {
                                    webViewInstance?.goBack()
                                } else {
                                    onDismiss()
                                }
                            }) {
                                Icon(
                                    imageVector = if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                    contentDescription = if (canGoBack) "Назад" else "Закрыть",
                                    tint = TextPrimary
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                errorMessage = null
                                isTokenHandled = false
                                isLoading = false
                                webViewInstance?.reload()
                            }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить",
                                    tint = TextPrimary
                                )
                            }
                            if (canGoBack) {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = TextPrimary
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                    )

                    // Loading progress indicator
                    if (webProgress in 1..99 && !isLoading) {
                        LinearProgressIndicator(
                            progress = { webProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = PrimaryBlue,
                            trackColor = SurfaceVariantDark
                        )
                    }

                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    "Не удалось создать звонок",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    errorMessage!!,
                                    color = AccentRed,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = onDismiss) {
                                        Text("Закрыть")
                                    }
                                    Button(
                                        onClick = {
                                            errorMessage = null
                                            isTokenHandled = false
                                            isLoading = false
                                            webViewInstance?.reload()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                    ) {
                                        Text("Повторить")
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewInstance = this
                                        setBackgroundColor(android.graphics.Color.parseColor("#0B0F19"))
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)

                                        // Plant stored cookies if available
                                        val stored = VKCookieStore.load()
                                        if (stored != null) {
                                            val domains = listOf(".vk.ru", ".vk.com", ".login.vk.ru", ".login.vk.com", ".oauth.vk.ru", ".oauth.vk.com", ".id.vk.ru", ".id.vk.com")
                                            for (item in stored.cookieHeader.split(";")) {
                                                val part = item.trim()
                                                if (part.startsWith("remixsid=") || part.startsWith("p=")) {
                                                    for (d in domains) {
                                                        cm.setCookie(d, "$part; path=/; domain=$d; Secure")
                                                    }
                                                }
                                            }
                                            cm.flush()
                                        }

                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            setSupportMultipleWindows(false)
                                            javaScriptCanOpenWindowsAutomatically = true
                                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                            allowFileAccess = false
                                            allowContentAccess = false
                                            cacheMode = WebSettings.LOAD_DEFAULT
                                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                webProgress = newProgress
                                                canGoBack = view?.canGoBack() == true
                                                inspectUrl(view?.url, view)
                                            }
                                        }

                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                val uri = request?.url ?: return false
                                                val url = uri.toString()
                                                val scheme = uri.scheme?.lowercase() ?: return false

                                                inspectUrl(url, view)
                                                inspectUrl(view?.url, view)

                                                if (url.contains("access_token=") || isOAuthBlankPage(url)) {
                                                    return true
                                                }

                                                // Allow standard web schemes inside WebView
                                                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                    return false
                                                }

                                                // For market://, android-app://, vk:, vkid:, etc.:
                                                // Never let Chromium attempt to navigate to unknown schemes or redirect out to Play Store.
                                                // Silently return true so web scripts continue in-page and show standard input fields.
                                                return true
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBack = view?.canGoBack() == true
                                                inspectUrl(url, view)
                                                inspectUrl(view?.url, view)
                                                harvestCookies(cm)
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                inspectUrl(url, view)
                                                inspectUrl(view?.url, view)
                                                harvestCookies(cm)

                                                view?.evaluateJavascript("window.location.href") { jsUrl ->
                                                    inspectUrl(jsUrl, view)
                                                }

                                                // Dismiss "Войти через приложение" bottom sheet if present, favoring manual entry
                                                view?.evaluateJavascript("""
                                                    (function() {
                                                        var btns = document.querySelectorAll('button, span, div, a');
                                                        for (var i = 0; i < btns.length; i++) {
                                                            var t = btns[i].innerText;
                                                            if (t && (t.indexOf('Ввести данные вручную') !== -1 || t.indexOf('вручную') !== -1)) {
                                                                btns[i].click();
                                                                break;
                                                            }
                                                        }
                                                    })();
                                                """.trimIndent(), null)

                                                if (url?.contains("id.vk.ru") == true || url?.contains("id.vk.com") == true) {
                                                    statusText = "Войдите или нажмите «Продолжить как…»"
                                                } else if (url?.contains("oauth.vk.ru") == true || url?.contains("oauth.vk.com") == true) {
                                                    statusText = "Подтвердите доступ к звонкам («Разрешить»)"
                                                }
                                            }

                                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                                super.doUpdateVisitedHistory(view, url, isReload)
                                                inspectUrl(url, view)
                                                inspectUrl(view?.url, view)
                                            }

                                            override fun onReceivedError(
                                                view: WebView?,
                                                request: WebResourceRequest?,
                                                error: WebResourceError?
                                            ) {
                                                // NEVER call super.onReceivedError() — it overrides the page with Chromium's default error page!
                                                val reqUrl = request?.url?.toString() ?: ""
                                                val scheme = request?.url?.scheme?.lowercase()

                                                if (scheme != "http" && scheme != "https") return
                                                if (error?.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return
                                                if (request?.isForMainFrame != true) return
                                                if (reqUrl.contains("access_token=") || isOAuthBlankPage(reqUrl)) return

                                                val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                                errorMessage = "$desc (код: ${error?.errorCode ?: -1})"
                                            }
                                        }

                                        loadUrl("https://oauth.vk.ru/authorize?client_id=$VK_OAUTH_CLIENT_ID&scope=calls&response_type=token&display=mobile&forced_color_scheme_mode=dark")
                                    }
                                }
                            )

                            // Continuous background watcher: catches client-side redirects and hash tokens within 250ms
                            LaunchedEffect(Unit) {
                                while (isActive && !isTokenHandled) {
                                    webViewInstance?.let { wv ->
                                        inspectUrl(wv.url, wv)
                                        wv.evaluateJavascript("window.location.href") { jsUrl ->
                                            inspectUrl(jsUrl, wv)
                                        }
                                    }
                                    delay(250)
                                }
                            }

                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(BgDark.copy(alpha = 0.95f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = PrimaryBlue)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            statusText,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isOAuthBlankPage(rawUrl: String?): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    val clean = rawUrl.trim('"', '\'', ' ')
    // Ensure we NEVER treat the login / 2FA screen as a blank page
    if (clean.contains("id.vk.com") || clean.contains("id.vk.ru")) return false

    return try {
        val uri = Uri.parse(clean)
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path ?: ""
        (host.contains("oauth.vk.") || host.contains("vk.com") || host.contains("vk.ru")) &&
                (path == "/blank.html" || path.endsWith("/blank.html"))
    } catch (_: Exception) {
        false
    }
}

private fun extractAccessToken(rawUrl: String?): String? {
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

    // Substring fallback
    val tokenPart = clean.substringAfter("access_token=")
    val token = tokenPart.substringBefore('&')
        .substringBefore('#')
        .substringBefore(';')
        .substringBefore('"')
        .substringBefore('\'')
        .trim()
    return if (token.isNotEmpty()) token else null
}

private fun harvestCookies(cookieManager: CookieManager) {
    val urls = listOf("https://vk.ru", "https://vk.com", "https://login.vk.ru", "https://login.vk.com", "https://id.vk.ru", "https://id.vk.com", "https://oauth.vk.ru", "https://oauth.vk.com")
    var remixsid: String? = null
    var p: String? = null

    for (url in urls) {
        val raw = cookieManager.getCookie(url) ?: continue
        for (item in raw.split(";")) {
            val trimmed = item.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name == "remixsid" && value.isNotEmpty() && value != "DELETED") {
                    remixsid = value
                }
                if (name == "p" && value.isNotEmpty() && value != "DELETED") {
                    p = value
                }
            }
        }
    }

    if (remixsid != null) {
        val header = if (!p.isNullOrEmpty()) "remixsid=$remixsid; p=$p" else "remixsid=$remixsid"
        val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
        VKCookieStore.save(header, expiry)
        VKCookieStore.isEnabled = true
    }
}

private suspend fun createCallWithToken(token: String, callback: (link: String?, error: String?) -> Unit) = withContext(Dispatchers.IO) {
    val hosts = listOf("api.vk.ru", "api.vk.com")
    var lastError = "Не удалось связаться с серверами VK"

    for (host in hosts) {
        try {
            val endpoint = "https://$host/method/calls.start"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.setRequestProperty("User-Agent", "VKAndroidApp/8.0-12345 (Android 14; SDK 34; arm64-v8a)")

            val postData = "v=5.276&access_token=$token"
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val response = reader.readText()
            reader.close()

            Log.d(TAG, "calls.start response: $response")

            val map = Gson().fromJson(response, Map::class.java)
            if (map.containsKey("error")) {
                val errMap = map["error"] as? Map<*, *>
                val msg = errMap?.get("error_msg") as? String ?: "VK error"
                val code = (errMap?.get("error_code") as? Number)?.toInt() ?: 0
                lastError = "$msg (код: $code)"
                if (code == 5) {
                    VKCookieStore.clearToken()
                }
                continue
            }

            val respMap = map["response"] as? Map<*, *>
            val joinLink = respMap?.get("join_link") as? String
            if (!joinLink.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    callback(joinLink, null)
                }
                return@withContext
            }
        } catch (e: Exception) {
            Log.w(TAG, "calls.start on $host failed", e)
            lastError = e.message ?: "Сетевая ошибка"
        }
    }

    withContext(Dispatchers.Main) {
        callback(null, lastError)
    }
}
