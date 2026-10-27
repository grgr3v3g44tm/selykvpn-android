package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VKAuthActivity"
private const val VK_LOGIN_URL = "https://m.vk.ru/login?lang=ru"
private const val VK_OAUTH_CLIENT_ID = "6287487"
private const val VK_OAUTH_REDIRECT_URI = "https://oauth.vk.com/blank.html"
private const val VK_OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$VK_OAUTH_CLIENT_ID&scope=calls,offline&redirect_uri=$VK_OAUTH_REDIRECT_URI&response_type=token&display=mobile&v=5.131"

/**
 * Dedicated fullscreen Activity for VK Authentication and VK Call Creation.
 * Running in its own Window avoids all Jetpack Compose AndroidViewHolder hardware
 * compositing and clipping bugs on real devices (MediaTek / Mali GPUs, ColorOS, MIUI, etc.).
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
                        if (mode == MODE_LOGIN) {
                            "Вход в учетную запись VK…"
                        } else if (hasStoredSession) {
                            "Подтвердите создание звонка…"
                        } else {
                            "Войдите в аккаунт VK для создания звонка…"
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

                // Fast path for call creation: try stored valid token first
                LaunchedEffect(Unit) {
                    if (mode == MODE_CREATE_CALL) {
                        val token = VKCookieStore.getToken()
                        if (!token.isNullOrBlank()) {
                            isExecutingApi = true
                            statusText = "Создаем звонок через сохраненную авторизацию…"
                            createCallWithToken(token) { link, err ->
                                if (link != null) {
                                    finishWithCallLink(link)
                                } else {
                                    VKCookieStore.clearToken()
                                    isExecutingApi = false
                                    statusText = "Подтвердите аккаунт для создания звонка…"
                                }
                            }
                        }
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
                                        contentDescription = "Создать в приложении/браузере",
                                        tint = TextSecondary
                                    )
                                }

                                IconButton(onClick = {
                                    VKCookieStore.clearCookies()
                                    VKCookieStore.clearToken()
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                    hasRedirectedToOAuth = false
                                    statusText = if (mode == MODE_LOGIN) "Вход в учетную запись VK…" else "Войдите в аккаунт VK для создания звонка…"
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
                                            setSupportMultipleWindows(false)
                                            javaScriptCanOpenWindowsAutomatically = true
                                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                            allowFileAccess = false
                                            allowContentAccess = false
                                            cacheMode = WebSettings.LOAD_DEFAULT
                                            useWideViewPort = true
                                            loadWithOverviewMode = true
                                            textZoom = 100

                                            val defaultUa = userAgentString ?: ""
                                            userAgentString = defaultUa.replace("; wv", "")
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                webProgress = newProgress
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(view?.url, this@apply)
                                            }

                                            override fun onJsBeforeUnload(
                                                view: WebView?,
                                                url: String?,
                                                message: String?,
                                                result: android.webkit.JsResult?
                                            ): Boolean {
                                                result?.confirm()
                                                return true
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
                                                        failingUrl.contains("vkuserphoto.ru") ||
                                                        failingUrl.contains("apptracer.ru") ||
                                                        failingUrl.contains("persiq.vk.ru")
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

                                                // 1. Check if token is already in URL path or fragment
                                                val directToken = extractAccessToken(url)
                                                if (directToken != null) {
                                                    handleTokenFound(directToken, view ?: this@apply)
                                                    return true
                                                }

                                                inspectForSuccess(url, view ?: this@apply)
                                                checkLoginLandingPage(url, view ?: this@apply)

                                                if (isOAuthBlankPage(url)) {
                                                    return false
                                                }

                                                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                    return false
                                                }

                                                // 2. Handle non-HTTP schemes (intent:, vkid:, vkconnect:, etc.)
                                                var fallbackUrl: String? = null
                                                if (url.startsWith("intent:") || url.startsWith("android-app:")) {
                                                    try {
                                                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                                        fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                        if (fallbackUrl.isNullOrEmpty()) {
                                                            val data = intent.dataString
                                                            if (!data.isNullOrEmpty() && (data.startsWith("http://") || data.startsWith("https://"))) {
                                                                fallbackUrl = data
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "Failed parsing intent URI: $url", e)
                                                    }
                                                }

                                                if (fallbackUrl.isNullOrEmpty()) {
                                                    fallbackUrl = uri.getQueryParameter("fallback_url")
                                                        ?: uri.getQueryParameter("browser_fallback_url")
                                                        ?: uri.getQueryParameter("redirect_uri")
                                                }

                                                if (!fallbackUrl.isNullOrEmpty() && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                // If VK ID deep link has no fallback URL, do NOT freeze in a loop. Load OAuth URL directly.
                                                if (url.startsWith("vkid:") || url.startsWith("vkconnect:") || url.startsWith("vk:")) {
                                                    view?.loadUrl(VK_OAUTH_URL)
                                                    return true
                                                }

                                                return true
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(url, view ?: this@apply)
                                                checkLoginLandingPage(url, view ?: this@apply)
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                inspectForSuccess(url, view ?: this@apply)
                                                checkLoginLandingPage(url, view ?: this@apply)

                                                view?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsHrefAndHash ->
                                                    inspectForSuccess(jsHrefAndHash, view)
                                                    checkLoginLandingPage(jsHrefAndHash, view)
                                                }

                                                // Auto-dismiss bottom promotional sheet ("Sign in without password or code")
                                                view?.evaluateJavascript("""
                                                    (function() {
                                                        var tries = 0;
                                                        var t = setInterval(function() {
                                                            tries++;
                                                            try {
                                                                var btn = document.querySelector(".vkuiIcon--cancel_outline_28, [aria-label='Close'], [aria-label='Закрыть'], .vkuiModalCardBase__dismiss")?.closest("button");
                                                                if (btn) {
                                                                    btn.click();
                                                                    clearInterval(t);
                                                                }
                                                            } catch(e) {}
                                                            if (tries > 25) clearInterval(t);
                                                        }, 200);
                                                    })();
                                                """.trimIndent(), null)
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

                                                if (reqUrl.contains("vk.ru")) {
                                                    val fallbackUrl = reqUrl.replace("vk.ru", "vk.com")
                                                    Log.w(TAG, "Failed loading $reqUrl, falling back to $fallbackUrl")
                                                    view?.loadUrl(fallbackUrl)
                                                    return
                                                }

                                                val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                                loadError = "$desc (код: ${error?.errorCode ?: -1})"
                                            }
                                        }

                                        // Ensure Russian language cookies
                                        plantLanguageCookies(cm)

                                        // Plant stored cookies if available for instant seamless login
                                        val hasStoredSession = VKCookieStore.hasValidLogin()
                                        val initialUrl = if (hasStoredSession && mode == MODE_CREATE_CALL) {
                                            val stored = VKCookieStore.load()
                                            if (stored != null) {
                                                plantCookies(cm, stored.cookieHeader)
                                            }
                                            VK_OAUTH_URL
                                        } else {
                                            VK_LOGIN_URL
                                        }
                                        loadUrl(initialUrl)
                                    }
                                }
                            )

                            // Background watcher: checks cookies and hash changes rapidly
                            LaunchedEffect(Unit) {
                                val cm = CookieManager.getInstance()
                                while (isActive && !isDone) {
                                    // 1. Check cookies for login landing
                                    checkCookies(cm) { header ->
                                        val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
                                        VKCookieStore.save(header, expiry)

                                        if (mode == MODE_LOGIN) {
                                            finishWithLoginSuccess(header)
                                        } else if (mode == MODE_CREATE_CALL && !hasRedirectedToOAuth) {
                                            hasRedirectedToOAuth = true
                                            statusText = "Вход выполнен! Подтверждаем создание звонка…"
                                            webViewInstance?.post {
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

                                    delay(400)
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
            VKCookieStore.saveToken(token)
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
                    VKCookieStore.saveToken(hashToken)
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
            val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
            VKCookieStore.save(cookieHeader, expiry)
            VKCookieStore.isEnabled = true
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

    private fun checkLoginLandingPage(url: String?, webView: WebView) {
        if (isDone || url.isNullOrBlank()) return
        val clean = url.lowercase()
        val isLanding = clean.contains("/feed") ||
                clean.contains("id.vk.") ||
                clean.contains("/account") ||
                clean.contains("vk.ru/call/join") ||
                clean.contains("vk.com/call/join")

        if (isLanding) {
            val cm = CookieManager.getInstance()
            checkCookies(cm) { header ->
                val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
                VKCookieStore.save(header, expiry)
                if (mode == MODE_LOGIN) {
                    finishWithLoginSuccess(header)
                } else if (mode == MODE_CREATE_CALL && !hasRedirectedToOAuth) {
                    hasRedirectedToOAuth = true
                    onApiExecutionStateChange?.invoke(false, "Вход выполнен! Подтверждаем создание звонка…")
                    webView.post {
                        webView.loadUrl(VK_OAUTH_URL)
                    }
                }
            }
        }
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

private fun plantCookies(cookieManager: CookieManager, cookieHeader: String) {
    var remixsid: String? = null
    var p: String? = null

    for (part in cookieHeader.split(";")) {
        val trimmed = part.trim()
        if (trimmed.startsWith("remixsid=")) {
            remixsid = trimmed.substringAfter("remixsid=").trim()
        }
        if (trimmed.startsWith("p=")) {
            p = trimmed.substringAfter("p=").trim()
        }
    }

    val domains = listOf("vk.ru", "vk.com", "login.vk.ru", "login.vk.com", "id.vk.ru", "id.vk.com", "oauth.vk.ru", "oauth.vk.com", "m.vk.ru", "m.vk.com")

    if (!remixsid.isNullOrBlank()) {
        for (d in domains) {
            val clean = d.removePrefix(".")
            cookieManager.setCookie("https://$clean/", "remixsid=$remixsid; Domain=.$clean; Path=/; Secure; SameSite=None")
        }
    }

    if (!p.isNullOrBlank()) {
        for (d in domains) {
            val clean = d.removePrefix(".")
            cookieManager.setCookie("https://$clean/", "p=$p; Domain=.$clean; Path=/; Secure; SameSite=None")
        }
    }

    plantLanguageCookies(cookieManager)
    cookieManager.flush()
}

private fun plantLanguageCookies(cookieManager: CookieManager) {
    val langDomains = listOf("vk.ru", "vk.com", "m.vk.ru", "m.vk.com", "id.vk.ru", "id.vk.com", "login.vk.ru", "login.vk.com", "oauth.vk.ru", "oauth.vk.com")
    for (d in langDomains) {
        val clean = d.removePrefix(".")
        cookieManager.setCookie("https://$clean/", "remixlang=0; Domain=.$clean; Path=/; Secure; SameSite=None")
    }
    cookieManager.flush()
}

private fun checkCookies(cookieManager: CookieManager, onFound: (String) -> Unit) {
    val urls = listOf(
        "https://vk.ru",
        "https://vk.com",
        "https://m.vk.ru",
        "https://m.vk.com",
        "https://login.vk.ru",
        "https://login.vk.com",
        "https://id.vk.ru",
        "https://id.vk.com",
        "https://oauth.vk.ru",
        "https://oauth.vk.com"
    )

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

    if (!remixsid.isNullOrBlank()) {
        val header = if (!p.isNullOrBlank()) "remixsid=$remixsid; p=$p" else "remixsid=$remixsid"
        onFound(header)
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
        (host.contains("oauth.vk.") || host.contains("vk.com") || host.contains("vk.ru")) &&
                (path == "/blank.html" || path.endsWith("/blank.html"))
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

suspend fun createCallWithToken(
    token: String,
    callback: (link: String?, error: String?) -> Unit
) = withContext(Dispatchers.IO) {
    val hosts = listOf("api.vk.me", "api.vk.ru", "api.vk.com")
    var lastError = "Не удалось связаться с серверами VK"

    for (host in hosts) {
        for (method in listOf("GET", "POST")) {
            try {
                val endpoint = if (method == "GET") {
                    "https://$host/method/calls.start?v=5.276&access_token=$token"
                } else {
                    "https://$host/method/calls.start"
                }
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "VKAndroidApp/8.0 (Android 14)")
                conn.setRequestProperty("Accept", "application/json")

                if (method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val postData = "v=5.276&access_token=$token".toByteArray(Charsets.UTF_8)
                    conn.outputStream.use { it.write(postData) }
                }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                conn.disconnect()

                Log.d(TAG, "calls.start on $host ($method) response: $response")

                val map = Gson().fromJson(response, Map::class.java)
                if (map.containsKey("error")) {
                    val errMap = map["error"] as? Map<*, *>
                    val msg = errMap?.get("error_msg") as? String ?: "VK error"
                    val code = (errMap?.get("error_code") as? Number)?.toInt() ?: 0
                    lastError = "$msg (код: $code)"
                    Log.w(TAG, "calls.start returned VK error on $host: $lastError")
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
                Log.w(TAG, "calls.start on $host ($method) failed", e)
                lastError = e.message ?: "Сетевая ошибка"
            }
        }
    }

    withContext(Dispatchers.Main) {
        callback(null, lastError)
    }
}
