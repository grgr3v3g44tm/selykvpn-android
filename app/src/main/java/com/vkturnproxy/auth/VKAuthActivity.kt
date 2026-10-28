package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.vkturnproxy.service.VkTurnVpnService
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VKAuthActivity"

// 1-to-1 parity with anton48/vk-turn-proxy-ios & SpaceNeuroX VkAuthWebViewManager
private const val VK_LOGIN_URL = "https://m.vk.ru/login"
private const val VK_OAUTH_CLIENT_ID = "6287487"
private const val VK_OAUTH_REDIRECT_HOST = "oauth.vk.com"
private const val VK_OAUTH_REDIRECT_PATH = "/blank.html"
private const val VK_OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$VK_OAUTH_CLIENT_ID&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html&response_type=token&scope=messages,calls&state=vkturn&v=5.199"

/**
 * Dedicated fullscreen Activity for VK Authentication and VK Call Creation.
 * Exactly implements the two-phase workflow from iOS TestFlight:
 * 1. MODE_LOGIN: Opens https://vk.ru/, single-window, polls CookieManager for remixsid (.vk.ru) + p (.login.vk.ru)
 * 2. MODE_CREATE_CALL: Plants remixsid on .vk.ru and p on .login.vk.ru, loads oauth.vk.ru/authorize,
 *    intercepts redirect to /blank.html#access_token=..., and calls calls.start via VK API.
 */
class VKAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_LOGIN = "mode_login"
        const val MODE_CREATE_CALL = "mode_create_call"

        const val EXTRA_CALL_LINK = "extra_call_link"
        const val EXTRA_COOKIE_HEADER = "extra_cookie_header"

        fun createLoginIntent(context: Context): Intent {
            return Intent(context, VKAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_LOGIN)
            }
        }

        fun createCallIntent(context: Context): Intent {
            return Intent(context, VKAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CREATE_CALL)
            }
        }
    }

    private var mode = MODE_CREATE_CALL
    private var webViewInstance: WebView? = null
    private var isDone = false
    private var hasRedirectedToOAuth = false
    private var onApiExecutionStateChange: ((Boolean, String) -> Unit)? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CREATE_CALL

        // Ensure direct network routing for authentication
        if (VkTurnVpnService.statusFlow.value != VpnStatus.DISCONNECTED) {
            Log.i(TAG, "Stopping active VPN tunnel for direct VK connection")
            VkTurnVpnService.stop(this)
        }

        setContent {
            VkTurnTheme {
                val hasStoredSession = remember { VKCookieStore.hasValidLogin() }
                var statusText by remember {
                    mutableStateOf(
                        if (mode == MODE_LOGIN || !hasStoredSession) {
                            "Войдите в аккаунт VK для привязки…"
                        } else {
                            "Подтвердите создание звонка…"
                        }
                    )
                }
                var webProgress by remember { mutableIntStateOf(0) }
                var canGoBack by remember { mutableStateOf(false) }
                var loadError by remember { mutableStateOf<String?>(null) }
                var isExecutingApi by remember { mutableStateOf(false) }
                val clipboardManager = LocalClipboardManager.current

                onApiExecutionStateChange = { running, text ->
                    isExecutingApi = running
                    statusText = text
                }

                BackHandler {
                    if (canGoBack && webViewInstance?.canGoBack() == true) {
                        webViewInstance?.goBack()
                    } else {
                        finishWithCancel()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        if (mode == MODE_LOGIN) "Вход ВКонтакте" else "Создание звонка VK",
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
                                        finishWithCancel()
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
                                // Paste from clipboard shortcut
                                IconButton(onClick = {
                                    val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                                    val foundLink = clipText.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") }
                                    if (!foundLink.isNullOrBlank()) {
                                        finishWithCallLink(foundLink)
                                    } else {
                                        Toast.makeText(this@VKAuthActivity, "В буфере нет ссылки на звонок (нужна ссылка vk.ru/call/join/...)", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = "Вставить из буфера",
                                        tint = PrimaryBlue
                                    )
                                }

                                IconButton(onClick = {
                                    loadError = null
                                    isExecutingApi = false
                                    webViewInstance?.reload()
                                }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Обновить",
                                        tint = TextPrimary
                                    )
                                }

                                IconButton(onClick = {
                                    try {
                                        val url = if (mode == MODE_LOGIN) "https://vk.ru" else "https://vk.ru/calls"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(browserIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(this@VKAuthActivity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.OpenInNew,
                                        contentDescription = "Открыть в браузере",
                                        tint = TextSecondary
                                    )
                                }

                                IconButton(onClick = {
                                    VKCookieStore.clearCookies()
                                    hasRedirectedToOAuth = false
                                    statusText = "Войдите в аккаунт VK…"
                                    webViewInstance?.loadUrl(VK_LOGIN_URL)
                                    Toast.makeText(this@VKAuthActivity, "Сессия сброшена", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = "Сменить аккаунт",
                                        tint = PrimaryBlue
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                        )
                    },
                    containerColor = Color.White
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color.White)
                    ) {
                        // Progress bar at top
                        if (webProgress in 1..99 && !isExecutingApi) {
                            LinearProgressIndicator(
                                progress = { webProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = PrimaryBlue,
                                trackColor = SurfaceVariantDark
                            )
                        }

                        if (loadError != null) {
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
                                        "Не удалось загрузить страницу VK",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        loadError!!,
                                        color = AccentRed,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(onClick = { finishWithCancel() }) {
                                            Text("Отмена", color = TextSecondary)
                                        }
                                        Button(
                                            onClick = {
                                                loadError = null
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
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewInstance = this
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        setBackgroundColor(android.graphics.Color.WHITE)

                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)

                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            // Modern Mobile Chrome UA
                                            userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                            // Enable multiple windows + onCreateWindow to prevent window.open / VK ID promise hangs
                                            setSupportMultipleWindows(true)
                                            javaScriptCanOpenWindowsAutomatically = true
                                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                            allowFileAccess = false
                                            allowContentAccess = false
                                            cacheMode = WebSettings.LOAD_DEFAULT
                                            useWideViewPort = true
                                            loadWithOverviewMode = true
                                            textZoom = 100
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCreateWindow(
                                                view: WebView,
                                                isDialog: Boolean,
                                                isUserGesture: Boolean,
                                                resultMsg: android.os.Message
                                            ): Boolean {
                                                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                                transport.webView = view
                                                resultMsg.sendToTarget()
                                                return true
                                            }

                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                webProgress = newProgress
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(view?.url, this@apply)
                                            }
                                        }

                                        webViewClient = object : WebViewClient() {
                                            override fun onReceivedSslError(
                                                view: WebView?,
                                                handler: SslErrorHandler?,
                                                error: SslError?
                                            ) {
                                                val failingUrl = error?.url?.lowercase() ?: ""
                                                val isVkDomain = failingUrl.isEmpty() ||
                                                        failingUrl.contains("vk.ru") ||
                                                        failingUrl.contains("vk.com") ||
                                                        failingUrl.contains("mail.ru") ||
                                                        failingUrl.contains("ok.ru") ||
                                                        failingUrl.contains("okcdn.ru") ||
                                                        failingUrl.contains("vk-portal.net") ||
                                                        failingUrl.contains("userapi.com") ||
                                                        failingUrl.contains("vkuserphoto.ru")
                                                if (isVkDomain) {
                                                    Log.i(TAG, "Proceeding Russian CA SSL certificate for $failingUrl")
                                                    handler?.proceed()
                                                } else {
                                                    super.onReceivedSslError(view, handler, error)
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                val uri = request?.url ?: return false
                                                val url = uri.toString()
                                                val scheme = uri.scheme?.lowercase() ?: return false

                                                // Check for OAuth blank.html redirect
                                                if (isOAuthBlankPage(url)) {
                                                    val token = extractAccessToken(url)
                                                    if (token != null) {
                                                        handleTokenFound(token, view ?: this@apply)
                                                        return true
                                                    }
                                                }

                                                // Extract access token if present in any URL or fragment
                                                val directToken = extractAccessToken(url)
                                                if (directToken != null) {
                                                    handleTokenFound(directToken, view ?: this@apply)
                                                    return true
                                                }

                                                // Block external deep links (vk://, intent:, android-app:) so they don't escape WebView and hang
                                                if (url.startsWith("intent:") || url.startsWith("vk://") || url.startsWith("android-app:")) {
                                                    Log.i(TAG, "Suppressed external auth intent: $url")
                                                    return true
                                                }

                                                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                    return false
                                                }

                                                return true
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(url, view ?: this@apply)
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(url, view ?: this@apply)

                                                view?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsHrefAndHash ->
                                                    inspectForSuccess(jsHrefAndHash, view)
                                                }
                                            }

                                            override fun onReceivedError(
                                                view: WebView?,
                                                request: WebResourceRequest?,
                                                error: WebResourceError?
                                            ) {
                                                val reqUrl = request?.url?.toString() ?: ""
                                                val scheme = request?.url?.scheme?.lowercase()

                                                if (scheme != "http" && scheme != "https") return
                                                if (error?.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return
                                                if (request?.isForMainFrame != true) return
                                                if (reqUrl.contains("access_token=") || isOAuthBlankPage(reqUrl)) return

                                                val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                                loadError = "$desc (код: ${error?.errorCode ?: -1})"
                                            }
                                        }

                                        // Decide initial URL 1-to-1 with iOS TestFlight
                                        val hasValidLogin = VKCookieStore.hasValidLogin()
                                        if (mode == MODE_CREATE_CALL && hasValidLogin) {
                                            // User has saved login -> plant cookies and load OAuth directly with slight delay to ensure cookie sync
                                            hasRedirectedToOAuth = true
                                            VKCookieStore.plantCookiesForOAuth(cm)
                                            postDelayed({
                                                loadUrl(VK_OAUTH_URL)
                                            }, 150)
                                        } else {
                                            // Clean login on https://m.vk.ru/login
                                            loadUrl(VK_LOGIN_URL)
                                        }
                                    }
                                }
                            )

                            // Background watcher: polls CookieManager every 1.2s (1-to-1 with iOS VKAuthWebView.swift)
                            LaunchedEffect(Unit) {
                                val cm = CookieManager.getInstance()
                                while (isActive && !isDone) {
                                    // 1. Poll for login completion (remixsid + p)
                                    val vkCookies = cm.getCookie("https://vk.ru/") ?: ""
                                    val loginCookies = cm.getCookie("https://login.vk.ru/") ?: ""
                                    val idCookies = cm.getCookie("https://id.vk.ru/") ?: ""
                                    val mCookies = cm.getCookie("https://m.vk.ru/") ?: ""

                                    val remixsid = VKCookieStore.extractCookieValue(vkCookies, "remixsid")
                                        ?: VKCookieStore.extractCookieValue(mCookies, "remixsid")
                                        ?: VKCookieStore.extractCookieValue(idCookies, "remixsid")
                                    val p = VKCookieStore.extractCookieValue(loginCookies, "p")
                                        ?: VKCookieStore.extractCookieValue(idCookies, "p")
                                        ?: VKCookieStore.extractCookieValue(vkCookies, "p")

                                    if (!remixsid.isNullOrBlank()) {
                                        val header = if (!p.isNullOrBlank()) "remixsid=$remixsid; p=$p" else "remixsid=$remixsid"
                                        val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000 // 1 year
                                        VKCookieStore.save(header, expiry)
                                        VKCookieStore.isEnabled = true

                                        if (mode == MODE_LOGIN) {
                                            finishWithLoginSuccess(header)
                                            break
                                        } else if (mode == MODE_CREATE_CALL && !hasRedirectedToOAuth) {
                                            hasRedirectedToOAuth = true
                                            statusText = "Вход выполнен! Открываем создание звонка…"
                                            withContext(Dispatchers.Main) {
                                                VKCookieStore.plantCookiesForOAuth(cm, header)
                                                webViewInstance?.loadUrl(VK_OAUTH_URL)
                                            }
                                        }
                                    }

                                    // 2. Poll URL and hash for instant access token capture
                                    webViewInstance?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsCombined ->
                                        if (!jsCombined.isNullOrBlank() && jsCombined.contains("access_token=")) {
                                            inspectForSuccess(jsCombined, webViewInstance ?: return@evaluateJavascript)
                                        }
                                    }

                                    delay(800)
                                }
                            }
                        }

                        // Fullscreen loading overlay when executing API
                        if (isExecutingApi) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(BgDark.copy(alpha = 0.92f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = PrimaryBlue)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        statusText,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
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

    private fun inspectForSuccess(url: String?, webView: WebView) {
        if (isDone || url.isNullOrBlank()) return

        val token = extractAccessToken(url)
        if (token != null) {
            if (mode == MODE_CREATE_CALL) {
                handleTokenFound(token, webView)
            } else {
                val header = VKCookieStore.getValidCookieHeader() ?: "access_token=$token"
                finishWithLoginSuccess(header)
            }
            return
        }

        if (isOAuthBlankPage(url)) {
            webView.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsCombined ->
                val hashToken = extractAccessToken(jsCombined)
                if (hashToken != null) {
                    if (mode == MODE_CREATE_CALL) {
                        handleTokenFound(hashToken, webView)
                    } else {
                        val header = VKCookieStore.getValidCookieHeader() ?: "access_token=$hashToken"
                        finishWithLoginSuccess(header)
                    }
                }
            }
        }
    }

    private fun handleTokenFound(token: String, webView: WebView) {
        if (isDone) return
        isDone = true
        onApiExecutionStateChange?.invoke(true, "Токен получен, создаем звонок…")

        try {
            webView.stopLoading()
        } catch (_: Exception) {}

        lifecycleScope.launch {
            createCallWithToken(token) { link, err ->
                if (link != null) {
                    finishWithCallLink(link)
                } else {
                    isDone = false
                    onApiExecutionStateChange?.invoke(false, "Ошибка создания звонка")
                    Toast.makeText(this@VKAuthActivity, err ?: "Ошибка создания звонка", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun finishWithCallLink(link: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_CALL_LINK, link)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithLoginSuccess(cookieHeader: String) {
        if (!isDone) {
            isDone = true
            Toast.makeText(this, "Вход выполнен успешно ✓", Toast.LENGTH_SHORT).show()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun finishWithCancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        try {
            webViewInstance?.apply {
                stopLoading()
                clearHistory()
                loadUrl("about:blank")
                onPause()
                destroy()
            }
        } catch (_: Exception) {}
        webViewInstance = null
        super.onDestroy()
    }
}

private fun isOAuthBlankPage(rawUrl: String?): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    val clean = rawUrl.trim('"', '\'', ' ')
    if (clean.contains("id.vk.com") || clean.contains("id.vk.ru")) return false

    return try {
        val uri = Uri.parse(clean)
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path ?: ""
        (host == VK_OAUTH_REDIRECT_HOST || host.contains("oauth.vk.") || host.contains("vk.com") || host.contains("vk.ru")) &&
                (path == VK_OAUTH_REDIRECT_PATH || path.endsWith("/blank.html"))
    } catch (_: Exception) {
        false
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

/**
 * Creates a VK call using calls.start API method.
 * Strict 1-to-1 parity with anton48/vk-turn-proxy-ios VKCallsAPI.swift:
 * - Pinned version 5.276
 * - Primary host api.vk.ru, fallback api.vk.com
 * - HTTP GET request with 20-second timeout
 * - Ephemeral access token (used once and immediately discarded)
 */
suspend fun createCallWithToken(
    token: String,
    callback: (link: String?, error: String?) -> Unit
) = withContext(Dispatchers.IO) {
    val hosts = listOf("api.vk.ru", "api.vk.com", "api.vk.me")
    var lastError = "Не удалось связаться с серверами VK"

    for (host in hosts) {
        try {
            val endpoint = "https://$host/method/calls.start?v=5.276&access_token=$token"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "VKTurnProxy/1.4")
            conn.setRequestProperty("Accept", "application/json")

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            conn.disconnect()

            Log.d(TAG, "calls.start on $host response: $response")

            val map = Gson().fromJson(response, Map::class.java)
            if (map.containsKey("error")) {
                val errMap = map["error"] as? Map<*, *>
                val msg = errMap?.get("error_msg") as? String ?: "VK error"
                val code = (errMap?.get("error_code") as? Number)?.toInt() ?: 0
                lastError = "VK error $code: $msg"
                Log.w(TAG, "calls.start returned error on $host: $lastError")
                if (code == 5) {
                    // Authorization failed
                    break
                }
                continue
            }

            val respMap = map["response"] as? Map<*, *>
            val joinLink = respMap?.get("join_link") as? String
            if (!joinLink.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    callback(joinLink.trim(), null)
                }
                return@withContext
            }
        } catch (e: Exception) {
            Log.w(TAG, "calls.start on $host failed", e)
            lastError = "Сетевая ошибка: ${e.message}"
        }
    }

    withContext(Dispatchers.Main) {
        callback(null, lastError)
    }
}
