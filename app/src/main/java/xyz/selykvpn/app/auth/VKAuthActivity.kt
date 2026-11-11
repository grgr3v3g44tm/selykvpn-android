package xyz.selykvpn.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VKAuthActivity"

private const val VK_LOGIN_URL = "https://m.vk.ru/login"
private const val VK_OAUTH_CLIENT_ID = "6287487"
private const val VK_OAUTH_REDIRECT_HOST = "oauth.vk.com"
private const val VK_OAUTH_REDIRECT_PATH = "/blank.html"
private const val VK_OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$VK_OAUTH_CLIENT_ID&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html&response_type=token&scope=messages,calls&state=vkturn&v=5.199"

/**
 * Dedicated Activity for VK Authentication and VK Call Creation.
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
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        statusText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
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
                                IconButton(onClick = { webViewInstance?.reload() }) {
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

                                    setupWebView(this) { p, back ->
                                        webProgress = p
                                        canGoBack = back
                                    }

                                    loadInitialUrl(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (webProgress in 1..99) {
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
                                    .background(Color.Black.copy(alpha = 0.5f)),
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

    private fun setupWebView(
        webView: WebView,
        onProgressChange: (Int, Boolean) -> Unit
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChange(newProgress, view?.canGoBack() == true)
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                // Auto-confirm beforeunload dialog so Android doesn't prompt "Leave or stay"
                result?.confirm()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { checkUrlForTokensOrCall(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { checkUrlForTokensOrCall(it) }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val nextUrl = request?.url?.toString() ?: return false
                if (checkUrlForTokensOrCall(nextUrl)) return true
                return false
            }
        }
    }

    private fun loadInitialUrl(webView: WebView) {
        if (mode == MODE_LOGIN || !VKCookieStore.hasValidLogin()) {
            VKCookieStore.clearWebViewCookies()
            webView.loadUrl(VK_LOGIN_URL)
        } else {
            VKCookieStore.plantCookiesIntoWebView()
            webView.loadUrl(VK_OAUTH_URL)
        }
    }

    private fun checkUrlForTokensOrCall(url: String): Boolean {
        if (isDone) return false
        val uri = Uri.parse(url)

        if (url.contains("call/join/")) {
            finishWithSuccess(url, VKCookieStore.getValidCookieHeader())
            return true
        }

        val host = uri.host ?: ""
        val path = uri.path ?: ""

        if ((host.contains(VK_OAUTH_REDIRECT_HOST) || host.contains("vk.ru")) &&
            path.contains(VK_OAUTH_REDIRECT_PATH)
        ) {
            val fragment = uri.fragment ?: ""
            if (fragment.contains("access_token=")) {
                val token = fragment.substringAfter("access_token=").substringBefore("&")
                if (token.isNotBlank()) {
                    isDone = true
                    onApiExecutionStateChange?.invoke(true, "Токен получен, создаём ссылку звонка…")
                    VKCallHelper.createCallWithToken(token) { callLink, err ->
                        runOnUiThread {
                            if (!callLink.isNullOrBlank()) {
                                finishWithSuccess(callLink, VKCookieStore.getValidCookieHeader())
                            } else {
                                onApiExecutionStateChange?.invoke(false, "Ошибка: $err")
                                Toast.makeText(this@VKAuthActivity, "Ошибка: $err", Toast.LENGTH_LONG).show()
                                isDone = false
                            }
                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun startCookiePolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cm = CookieManager.getInstance()
            while (!isDone) {
                delay(800)
                if (isDone) break

                val vkRuCookies = cm.getCookie("https://vk.ru") ?: ""
                val loginCookies = cm.getCookie("https://login.vk.ru") ?: ""
                val all = "$vkRuCookies; $loginCookies"

                val hasRemixsid = all.contains("remixsid=")
                val hasP = all.contains("p=")

                if (hasRemixsid && hasP) {
                    val oneYearAhead = System.currentTimeMillis() + (365L * 24 * 3600 * 1000)
                    VKCookieStore.saveSession(all, oneYearAhead)

                    if (mode == MODE_LOGIN) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@VKAuthActivity, "Авторизация VK успешна!", Toast.LENGTH_SHORT).show()
                            finishWithSuccess("", all)
                        }
                        break
                    } else if (!hasRedirectedToOAuth) {
                        hasRedirectedToOAuth = true
                        withContext(Dispatchers.Main) {
                            VKCookieStore.plantCookiesIntoWebView()
                            webViewInstance?.loadUrl(VK_OAUTH_URL)
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
        webViewInstance?.destroy()
        webViewInstance = null
        super.onDestroy()
    }
}
