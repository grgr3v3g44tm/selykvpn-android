package xyz.selykvpn.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.selykvpn.app.R
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import xyz.selykvpn.app.vpn.VpnController
import java.util.Collections

private const val TAG = "VKAuthActivity"

private const val VK_LOGIN_URL = "https://m.vk.ru/login"
private const val VK_OAUTH_CLIENT_ID = "6287487"
private const val VK_OAUTH_REDIRECT_HOST = "oauth.vk.com"
private const val VK_OAUTH_REDIRECT_PATH = "/blank.html"
private const val VK_OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$VK_OAUTH_CLIENT_ID&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html&response_type=token&scope=messages,calls&state=vkturn&v=5.199"

/**
 * Dedicated Activity for VK Authentication and VK Call Creation.
 * Aligned with legacy vkturn-android WebView fixes (multi-window support, external scheme blocking,
 * cookie extraction across .vk.ru/.vk.com, and api.vk.ru/com/me calls.start fallback).
 */
class VKAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_LOGIN = "mode_login"
        const val MODE_CREATE_CALL = "mode_create_call"

        const val EXTRA_CALL_LINK = "extra_call_link"
        const val EXTRA_COOKIE_HEADER = "extra_cookie_header"

        internal val BLOCKED_EXTERNAL_SCHEMES = setOf(
            "intent", "vk", "vkauthorize", "vkauth", "vkontakte", "vk-app", "android-app", "market", "tg"
        )

        internal fun isAllowedWebScheme(scheme: String?): Boolean {
            val s = scheme?.lowercase() ?: return false
            return s == "http" || s == "https" || s == "about" || s == "javascript" || s == "data"
        }

        internal fun shouldBlockScheme(scheme: String?): Boolean {
            val s = scheme?.lowercase() ?: return false
            return s in BLOCKED_EXTERNAL_SCHEMES || !isAllowedWebScheme(s)
        }

        internal fun isOAuthFlowUrl(rawUrl: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            val clean = rawUrl.trim('"', '\'', ' ')
            return try {
                val uri = java.net.URI(clean)
                val host = uri.host?.lowercase() ?: ""
                val lower = clean.lowercase()
                host.contains("oauth.vk.") ||
                    lower.contains("act=grant_access") ||
                    (host.contains("id.vk.") &&
                        (lower.contains("app_id=$VK_OAUTH_CLIENT_ID") ||
                            lower.contains("oauth.vk.com")))
            } catch (_: Exception) {
                false
            }
        }

        internal fun isVkLoggedInLandingUrl(rawUrl: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            val clean = rawUrl.trim('"', '\'', ' ')
            if (isOAuthFlowUrl(clean)) return false
            val lower = clean.lowercase()
            if (lower.contains("call/join/") ||
                lower.contains("access_token=") ||
                lower.contains("/login") ||
                lower.contains("/auth") ||
                lower.contains("/restore") ||
                lower.contains("/join") ||
                lower.contains("act=grant_access") ||
                lower.contains("/blank.html")
            ) {
                return false
            }
            return try {
                val uri = java.net.URI(clean)
                val host = uri.host?.lowercase() ?: return false
                val isVkHost = host == "m.vk.ru" || host == "vk.ru" ||
                    host == "m.vk.com" || host == "vk.com" ||
                    host == "id.vk.ru" || host == "id.vk.com"
                if (!isVkHost) return false
                val rawPath = uri.path
                val path = if (rawPath.isNullOrEmpty()) "/" else rawPath.lowercase()
                path == "/" ||
                    path.startsWith("/feed") ||
                    path.startsWith("/account") ||
                    path.startsWith("/settings") ||
                    path.startsWith("/menu") ||
                    path.startsWith("/im") ||
                    path.startsWith("/mail") ||
                    path.startsWith("/calls") ||
                    path.startsWith("/friends") ||
                    path.startsWith("/clips") ||
                    Regex("^/id\\d+.*").matches(path)
            } catch (_: Exception) {
                false
            }
        }

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

    @Volatile
    private var isDone = false

    @Volatile
    private var isApiInFlight = false
    @Volatile
    private var isTokenLookupInFlight = false
    private var hasRedirectedToOAuth = false
    private var oauthStartVisited = false
    private var bouncedBackToLoginFromOAuth = false
    private var oauthRedirectAttempts = 0
    private var staleCookieHeader: String? = null
    private val failedTokens = Collections.synchronizedSet(HashSet<String>())
    private var pageLoadWatchdogJob: Job? = null
    private var lastBlockedSchemeToastMs = 0L
    private var onApiExecutionStateChange: ((Boolean, String) -> Unit)? = null
    private var onErrorStateChange: ((String?) -> Unit)? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VKCookieStore.init(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CREATE_CALL

        // Ensure direct network routing for authentication
        if (VpnController.state.value !is VpnController.State.Idle) {
            Log.i(TAG, "Stopping active VPN tunnel for direct VK connection")
            VpnController.stop(this)
        }

        setContent {
            SelykVpnTheme {
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
                onErrorStateChange = { err ->
                    loadError = err
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
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        statusText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        contentDescription = if (canGoBack) "Назад" else "Закрыть"
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                                    val foundLink = clipText.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") }
                                    if (!foundLink.isNullOrBlank()) {
                                        finishWithSuccess(foundLink, VKCookieStore.getValidCookieHeader())
                                    } else {
                                        Toast.makeText(this@VKAuthActivity, "В буфере нет ссылки на звонок", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Вставить ссылку звонка")
                                }
                                IconButton(onClick = {
                                    try {
                                        val url = if (mode == MODE_LOGIN) "https://vk.ru" else "https://vk.ru/calls"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(browserIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(this@VKAuthActivity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в браузере")
                                }
                                IconButton(onClick = {
                                    VKCookieStore.clearSession()
                                    VKCookieStore.clearWebViewCookies()
                                    failedTokens.clear()
                                    hasRedirectedToOAuth = false
                                    oauthStartVisited = false
                                    bouncedBackToLoginFromOAuth = false
                                    staleCookieHeader = null
                                    isApiInFlight = false
                                    loadError = null
                                    statusText = "Войдите в аккаунт VK…"
                                    webViewInstance?.loadUrl(VK_LOGIN_URL)
                                    Toast.makeText(this@VKAuthActivity, "Сессия сброшена", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = "Сменить аккаунт")
                                }
                                IconButton(onClick = {
                                    loadError = null
                                    isApiInFlight = false
                                    failedTokens.clear()
                                    webViewInstance?.let { wv ->
                                        val cur = wv.url.orEmpty()
                                        if (cur.isBlank() || cur == "about:blank" || isOAuthBlankPage(cur)) {
                                            loadInitialUrl(wv)
                                        } else {
                                            wv.reload()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewInstance = this
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setBackgroundColor(android.graphics.Color.WHITE)

                                    setupWebView(this) { p, back ->
                                        webProgress = p
                                        canGoBack = back
                                    }

                                    loadInitialUrl(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (loadError != null) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp)
                                ) {
                                    Text(
                                        "Не удалось загрузить страницу VK",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        loadError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(onClick = { finishWithCancel() }) {
                                            Text("Отмена")
                                        }
                                        Button(onClick = {
                                            loadError = null
                                            isApiInFlight = false
                                            failedTokens.clear()
                                            webViewInstance?.let { wv ->
                                                val cur = wv.url.orEmpty()
                                                if (cur.isBlank() || cur == "about:blank" || isOAuthBlankPage(cur)) {
                                                    loadInitialUrl(wv)
                                                } else {
                                                    wv.reload()
                                                }
                                            }
                                        }) {
                                            Text(getString(R.string.vk_auth_retry))
                                        }
                                    }
                                }
                            }
                        }

                        if (webProgress in 1..99 && !isExecutingApi && loadError == null) {
                            LinearProgressIndicator(
                                progress = { webProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                            )
                        }

                        if (isExecutingApi) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    modifier = Modifier.padding(32.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        CircularProgressIndicator()
                                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        startCookiePolling()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        onProgressChange: (Int, Boolean) -> Unit
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.textZoom = 100

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChange(newProgress, view?.canGoBack() == true)
                if (newProgress >= 100) cancelPageLoadWatchdog()
                view?.url?.let { checkUrlForTokensOrCall(it, view) }
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                Log.w(TAG, "SSL error in VK WebView for ${error?.url}: $error")
                cancelPageLoadWatchdog()
                handler?.cancel()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onProgressChange(10, view?.canGoBack() == true)
                startPageLoadWatchdog(url)
                url?.let { checkUrlForTokensOrCall(it, view) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onProgressChange(100, view?.canGoBack() == true)
                cancelPageLoadWatchdog()
                if (url != null && oauthStartVisited && !bouncedBackToLoginFromOAuth && isVkLoginUrl(url)) {
                    Log.w(TAG, "VK OAuth bounced back to login page; clearing stale cached cookies")
                    bouncedBackToLoginFromOAuth = true
                    oauthStartVisited = false
                    hasRedirectedToOAuth = false
                    oauthRedirectAttempts = 0
                    staleCookieHeader = VKCookieStore.getValidCookieHeader()
                    VKCookieStore.clearSession()
                    VKCookieStore.clearWebViewCookies()
                }
                val handled = url?.let { checkUrlForTokensOrCall(it, view) } ?: false
                view?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsHrefAndHash ->
                    checkUrlForTokensOrCall(jsHrefAndHash, view)
                }
                if (!handled &&
                    mode == MODE_CREATE_CALL &&
                    !isDone &&
                    !isApiInFlight &&
                    !isTokenLookupInFlight &&
                    isVkLoggedInLandingUrl(url)
                ) {
                    val cm = CookieManager.getInstance()
                    val fullCookies = VKCookieStore.extractFullCookiesFromManager(cm)
                    if (!fullCookies.remixsid.isNullOrBlank()) {
                        val header = fullCookies.toCookieHeader()
                        if (header != staleCookieHeader) {
                            val oneYearAhead = System.currentTimeMillis() + (365L * 24 * 3600 * 1000)
                            VKCookieStore.saveSession(header, oneYearAhead)
                            tryObtainTokenFromSessionOrRedirectToOAuth(view, header)
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val nextUrl = uri.toString()
                val scheme = uri.scheme?.lowercase() ?: return false

                if (checkUrlForTokensOrCall(nextUrl, view)) return true

                // Block external app deep links so auth completes inside WebView
                if (shouldBlockScheme(scheme)) {
                    Log.i(TAG, "Blocked external intent scheme: $scheme")
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBlockedSchemeToastMs > 4_000L) {
                        lastBlockedSchemeToastMs = now
                        Toast.makeText(this@VKAuthActivity, R.string.vk_auth_external_blocked, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val reqUrl = request?.url?.toString() ?: ""
                val scheme = request?.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return
                if (error?.errorCode == ERROR_UNSUPPORTED_SCHEME) return
                if (request?.isForMainFrame != true) return
                if (reqUrl.contains("access_token=") || isOAuthBlankPage(reqUrl)) return

                cancelPageLoadWatchdog()
                val desc = error?.description?.toString() ?: "Сетевая ошибка"
                onErrorStateChange?.invoke("$desc (код: ${error?.errorCode ?: -1})")
            }
        }
    }

    private fun startPageLoadWatchdog(url: String?) {
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://")) || isOAuthBlankPage(url)) {
            return
        }
        pageLoadWatchdogJob?.cancel()
        pageLoadWatchdogJob = lifecycleScope.launch {
            delay(20_000L)
            if (!isDone && !isApiInFlight && !isFinishing && !isDestroyed) {
                Log.w(TAG, "WebView load timed out on $url")
                onErrorStateChange?.invoke(getString(R.string.vk_auth_timeout))
            }
        }
    }

    private fun cancelPageLoadWatchdog() {
        pageLoadWatchdogJob?.cancel()
        pageLoadWatchdogJob = null
    }

    private fun isVkLoginUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        return lower.contains("m.vk.ru/login") ||
            lower.contains("m.vk.com/login") ||
            lower.contains("vk.ru/login") ||
            lower.contains("vk.com/login")
    }

    private fun loadInitialUrl(webView: WebView) {
        if (mode == MODE_LOGIN || !VKCookieStore.hasValidLogin()) {
            oauthStartVisited = false
            hasRedirectedToOAuth = false
            oauthRedirectAttempts = 0
            staleCookieHeader = null
            VKCookieStore.clearWebViewCookies()
            webView.loadUrl(VK_LOGIN_URL)
        } else {
            hasRedirectedToOAuth = true
            oauthStartVisited = true
            oauthRedirectAttempts = 1
            staleCookieHeader = VKCookieStore.getValidCookieHeader()
            VKCookieStore.plantCookiesIntoWebView()
            webView.postDelayed({ webView.loadUrl(VK_OAUTH_URL) }, 150)
        }
    }

    private fun startCallCreationWithToken(token: String, webView: WebView?) {
        if (isDone || isApiInFlight || token in failedTokens) return
        isApiInFlight = true
        cancelPageLoadWatchdog()
        runOnUiThread {
            onApiExecutionStateChange?.invoke(true, "Токен получен, создаём ссылку звонка…")
            runCatching { webView?.stopLoading() }
        }
        VKCallHelper.createCallWithToken(token) { callLink, err, isBanned ->
            runOnUiThread {
                if (!callLink.isNullOrBlank()) {
                    finishWithSuccess(callLink, VKCookieStore.getValidCookieHeader())
                } else {
                    isApiInFlight = false
                    failedTokens.add(token)
                    val msg = err ?: "Ошибка создания звонка"
                    onApiExecutionStateChange?.invoke(false, msg)
                    val invalidAuth = isBanned || msg.contains("Ошибка VK 5")
                    if (invalidAuth) {
                        VKCookieStore.clearSession()
                        VKCookieStore.clearWebViewCookies()
                        hasRedirectedToOAuth = false
                        oauthStartVisited = false
                        oauthRedirectAttempts = 0
                        webView?.loadUrl(VK_LOGIN_URL)
                    } else {
                        // Navigate off blank.html#access_token=... and expose Retry overlay.
                        onErrorStateChange?.invoke(msg)
                        webView?.loadUrl(VK_OAUTH_URL)
                    }
                    Toast.makeText(this@VKAuthActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun tryObtainTokenFromSessionOrRedirectToOAuth(webView: WebView?, cookieHeader: String) {
        if (isDone || isApiInFlight || isTokenLookupInFlight) return
        isTokenLookupInFlight = true
        onApiExecutionStateChange?.invoke(true, "Получаем токен для создания звонка…")
        lifecycleScope.launch(Dispatchers.IO) {
            val token = runCatching {
                VKCallHelper.obtainAccessTokenViaHttp(this@VKAuthActivity, cookieHeader)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                isTokenLookupInFlight = false
                if (isDone || isApiInFlight) return@withContext
                if (!token.isNullOrBlank() && token !in failedTokens) {
                    startCallCreationWithToken(token, webView)
                    return@withContext
                }
                if (oauthRedirectAttempts < 2) {
                    oauthRedirectAttempts += 1
                    hasRedirectedToOAuth = true
                    oauthStartVisited = true
                    onApiExecutionStateChange?.invoke(false, "Вход выполнен! Открываем создание звонка…")
                    VKCookieStore.plantCookiesIntoWebView(cookieHeader)
                    webView?.loadUrl(VK_OAUTH_URL)
                } else {
                    onApiExecutionStateChange?.invoke(
                        false,
                        "Откройте звонки и нажмите «Создать звонок», ссылка перехватится автоматически"
                    )
                    val currentUrl = webView?.url.orEmpty().lowercase()
                    if (!currentUrl.contains("/calls")) {
                        webView?.loadUrl("https://vk.ru/calls")
                    }
                }
            }
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

    private fun checkUrlForTokensOrCall(rawUrl: String?, webView: WebView?): Boolean {
        if (isDone || isApiInFlight || rawUrl.isNullOrBlank()) return false
        val url = rawUrl.trim('"', '\'', ' ')

        if (url.contains("call/join/")) {
            val cleanLink = url.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") } ?: url
            finishWithSuccess(cleanLink, VKCookieStore.getValidCookieHeader())
            return true
        }

        val token = VKCallHelper.extractAccessToken(url)
        if (token != null) {
            if (token in failedTokens) return false
            if (mode == MODE_LOGIN) {
                val cmCookies = VKCookieStore.extractFullCookiesFromManager(CookieManager.getInstance())
                val header = if (!cmCookies.remixsid.isNullOrBlank()) {
                    val extracted = cmCookies.toCookieHeader()
                    if (extracted != staleCookieHeader) {
                        val oneYearAhead = System.currentTimeMillis() + (365L * 24 * 3600 * 1000)
                        VKCookieStore.saveSession(extracted, oneYearAhead)
                        extracted
                    } else null
                } else {
                    VKCookieStore.getValidCookieHeader()?.takeIf { VKCookieStore.extractCookieValue(it, "remixsid") != null }
                }
                if (!header.isNullOrBlank()) {
                    finishWithSuccess("", header)
                    return true
                }
                return false
            }

            startCallCreationWithToken(token, webView)
            return true
        }

        if (isOAuthBlankPage(url)) {
            webView?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsCombined ->
                if (!jsCombined.isNullOrBlank() && jsCombined.contains("access_token=")) {
                    checkUrlForTokensOrCall(jsCombined, webView)
                }
            }
        }
        return false
    }

    private fun startCookiePolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cm = CookieManager.getInstance()
            var lastSavedHeader: String? = null
            while (isActive && !isDone) {
                delay(800)
                if (isDone) break

                val fullCookies = VKCookieStore.extractFullCookiesFromManager(cm)
                if (!fullCookies.remixsid.isNullOrBlank()) {
                    val header = fullCookies.toCookieHeader()
                    if (header == staleCookieHeader) {
                        continue
                    }
                    if (header != lastSavedHeader) {
                        lastSavedHeader = header
                        val oneYearAhead = System.currentTimeMillis() + (365L * 24 * 3600 * 1000)
                        VKCookieStore.saveSession(header, oneYearAhead)
                    }

                    if (mode == MODE_LOGIN) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@VKAuthActivity, "Авторизация VK успешна!", Toast.LENGTH_SHORT).show()
                            finishWithSuccess("", header)
                        }
                        break
                    } else if (!hasRedirectedToOAuth && !isApiInFlight && !isTokenLookupInFlight) {
                        withContext(Dispatchers.Main) {
                            val currentUrl = webViewInstance?.url ?: ""
                            if (isOAuthFlowUrl(currentUrl)) {
                                hasRedirectedToOAuth = true
                                oauthStartVisited = true
                            } else {
                                hasRedirectedToOAuth = true
                                oauthStartVisited = true
                                tryObtainTokenFromSessionOrRedirectToOAuth(webViewInstance, header)
                            }
                        }
                    }
                }

                if (!isApiInFlight) {
                    withContext(Dispatchers.Main) {
                        webViewInstance?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsCombined ->
                            if (!jsCombined.isNullOrBlank() && jsCombined.contains("access_token=")) {
                                checkUrlForTokensOrCall(jsCombined, webViewInstance)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishWithSuccess(callLink: String, cookieHeader: String?) {
        if (isFinishing || isDestroyed) return
        cancelPageLoadWatchdog()
        isApiInFlight = false
        isDone = true
        val result = Intent().apply {
            putExtra(EXTRA_CALL_LINK, callLink)
            putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishWithCancel() {
        if (isFinishing || isDestroyed) return
        cancelPageLoadWatchdog()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        cancelPageLoadWatchdog()
        runCatching {
            webViewInstance?.apply {
                stopLoading()
                clearHistory()
                loadUrl("about:blank")
                onPause()
                destroy()
            }
        }
        webViewInstance = null
        super.onDestroy()
    }
}
