package xyz.selykvpn.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import xyz.selykvpn.app.vpn.VpnController

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
                                    hasRedirectedToOAuth = false
                                    loadError = null
                                    statusText = "Войдите в аккаунт VK…"
                                    webViewInstance?.loadUrl(VK_LOGIN_URL)
                                    Toast.makeText(this@VKAuthActivity, "Сессия сброшена", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = "Сменить аккаунт")
                                }
                                IconButton(onClick = {
                                    loadError = null
                                    isExecutingApi = false
                                    webViewInstance?.reload()
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
                        if (loadError != null) {
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
                                        webViewInstance?.reload()
                                    }) {
                                        Text("Повторить")
                                    }
                                }
                            }
                        } else {
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
                        }

                        if (webProgress in 1..99 && !isExecutingApi) {
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
                handler?.cancel()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onProgressChange(10, view?.canGoBack() == true)
                url?.let { checkUrlForTokensOrCall(it, view) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onProgressChange(100, view?.canGoBack() == true)
                url?.let { checkUrlForTokensOrCall(it, view) }
                view?.evaluateJavascript("window.location.href + ' ' + window.location.hash") { jsHrefAndHash ->
                    checkUrlForTokensOrCall(jsHrefAndHash, view)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val nextUrl = uri.toString()
                val scheme = uri.scheme?.lowercase() ?: return false

                if (checkUrlForTokensOrCall(nextUrl, view)) return true

                // Block external app deep links so auth completes inside WebView
                if (nextUrl.startsWith("intent:") || nextUrl.startsWith("vk://") || nextUrl.startsWith("android-app:")) {
                    Log.i(TAG, "Blocked external intent scheme: $scheme")
                    return true
                }

                return !(scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data")
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

                val desc = error?.description?.toString() ?: "Сетевая ошибка"
                onErrorStateChange?.invoke("$desc (код: ${error?.errorCode ?: -1})")
            }
        }
    }

    private fun loadInitialUrl(webView: WebView) {
        if (mode == MODE_LOGIN || !VKCookieStore.hasValidLogin()) {
            VKCookieStore.clearWebViewCookies()
            webView.loadUrl(VK_LOGIN_URL)
        } else {
            hasRedirectedToOAuth = true
            VKCookieStore.plantCookiesIntoWebView()
            webView.postDelayed({ webView.loadUrl(VK_OAUTH_URL) }, 150)
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
        if (isDone || rawUrl.isNullOrBlank()) return false
        val url = rawUrl.trim('"', '\'', ' ')

        if (url.contains("call/join/")) {
            val cleanLink = url.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") } ?: url
            finishWithSuccess(cleanLink, VKCookieStore.getValidCookieHeader())
            return true
        }

        val token = VKCallHelper.extractAccessToken(url)
        if (token != null) {
            if (mode == MODE_LOGIN) {
                val header = VKCookieStore.getValidCookieHeader() ?: "access_token=$token"
                finishWithSuccess("", header)
                return true
            }

            isDone = true
            runOnUiThread {
                onApiExecutionStateChange?.invoke(true, "Токен получен, создаём ссылку звонка…")
                runCatching { webView?.stopLoading() }
            }
            VKCallHelper.createCallWithToken(token) { callLink, err, isBanned ->
                runOnUiThread {
                    if (!callLink.isNullOrBlank()) {
                        finishWithSuccess(callLink, VKCookieStore.getValidCookieHeader())
                    } else {
                        isDone = false
                        onApiExecutionStateChange?.invoke(false, "Ошибка создания звонка")
                        if (isBanned) VKCookieStore.clearSession()
                        Toast.makeText(this@VKAuthActivity, err ?: "Ошибка создания звонка", Toast.LENGTH_LONG).show()
                    }
                }
            }
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

                val (remixsid, p) = VKCookieStore.extractCookiesFromManager(cm)
                if (!remixsid.isNullOrBlank()) {
                    val header = if (!p.isNullOrBlank()) "remixsid=$remixsid; p=$p" else "remixsid=$remixsid"
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
                    } else if (!hasRedirectedToOAuth) {
                        withContext(Dispatchers.Main) {
                            val currentUrl = webViewInstance?.url ?: ""
                            val isAlreadyInOAuth = currentUrl.contains("oauth.vk.") ||
                                currentUrl.contains("id.vk.") ||
                                currentUrl.contains("act=grant_access")
                            hasRedirectedToOAuth = true
                            if (!isAlreadyInOAuth) {
                                onApiExecutionStateChange?.invoke(false, "Вход выполнен! Открываем создание звонка…")
                                VKCookieStore.plantCookiesIntoWebView(header)
                                webViewInstance?.loadUrl(VK_OAUTH_URL)
                            }
                        }
                    }
                }

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

    private fun finishWithSuccess(callLink: String, cookieHeader: String?) {
        if (isFinishing || isDestroyed) return
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
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
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
