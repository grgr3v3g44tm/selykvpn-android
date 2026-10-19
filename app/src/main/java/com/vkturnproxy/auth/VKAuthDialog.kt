package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vkturnproxy.service.VkTurnVpnService
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "VKAuthDialog"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VKAuthDialog(
    onDismiss: () -> Unit,
    onSuccess: (cookieHeader: String) -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Войдите в аккаунт или создайте новый") }
    var isDone by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Handle physical back button
    BackHandler {
        if (canGoBack && webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onDismiss()
        }
    }

    // Ensure direct physical network routing (Wi-Fi/Cellular) while performing VK Auth
    LaunchedEffect(Unit) {
        if (VkTurnVpnService.statusFlow.value != VpnStatus.DISCONNECTED) {
            Log.i(TAG, "Stopping active VPN tunnel to ensure direct network connection for VK Auth")
            VkTurnVpnService.stop(context)
        }
    }

    fun handleSuccess(header: String) {
        if (!isDone) {
            isDone = true
            val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
            VKCookieStore.save(header, expiry)
            VKCookieStore.isEnabled = true
            onSuccess(header)
            onDismiss()
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

    // Fullscreen surface overlay inside primary Window — eliminates DialogWindow black screen bug
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Вход в VK (Cookie Auth)",
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
                        loadError = null
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

            // Progress indicator
            if (webProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { webProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = PrimaryBlue,
                    trackColor = SurfaceVariantDark
                )
            }

            // Content
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
                            "Не удалось загрузить страницу",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            loadError!!,
                            color = AccentRed,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                loadError = null
                                webViewInstance?.reload()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Text("Повторить попытку")
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                    fun inspectUrl(url: String?) {
                        if (url.isNullOrBlank()) return
                        val token = extractAccessToken(url)
                        if (token != null) {
                            VKCookieStore.saveToken(token)
                        }
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                setLayerType(View.LAYER_TYPE_NONE, null)
                                setBackgroundColor(android.graphics.Color.WHITE)
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    useWideViewPort = false
                                    loadWithOverviewMode = false
                                    textZoom = 100
                                    val defaultUa = userAgentString ?: ""
                                    userAgentString = defaultUa.replace("; wv", "").replace("Version/4.0 ", "")

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        isAlgorithmicDarkeningAllowed = false
                                    }
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                                    }
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        webProgress = newProgress
                                        canGoBack = view?.canGoBack() == true
                                        inspectUrl(view?.url)
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        if (resultMsg == null) return false
                                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                        transport.webView = view
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                        val failingUrl = error?.url?.lowercase() ?: ""
                                        val isVkDomain = failingUrl.contains("vk.ru") ||
                                                failingUrl.contains("vk.com") ||
                                                failingUrl.contains("mail.ru") ||
                                                failingUrl.contains("ok.ru") ||
                                                failingUrl.contains("okcdn.ru") ||
                                                failingUrl.contains("vk-portal.net") ||
                                                failingUrl.contains("userapi.com") ||
                                                failingUrl.contains("vkuserphoto.ru")
                                        if (isVkDomain) {
                                            Log.i(TAG, "Proceeding Russian CA SSL certificate for VK domain: $failingUrl")
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

                                        inspectUrl(url)

                                        // Allow standard web schemes to load inside WebView
                                        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                            return false
                                        }

                                        // Try extracting fallback URL for intent:, vk:, vkconnect:
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
                                                ?: uri.getQueryParameter("redirect_uri")
                                        }

                                        if (!fallbackUrl.isNullOrEmpty() && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                                            view?.loadUrl(fallbackUrl)
                                            return true
                                        }

                                        // Keep all VK authentication flows inside WebView — do NOT launch external VK app
                                        if (url.startsWith("vk:") || url.startsWith("vkconnect:") || url.contains("vk.com") || url.contains("vk.ru")) {
                                            return true
                                        }

                                        // Intercept other non-web schemes
                                        try {
                                            val intent = when {
                                                url.startsWith("intent:") -> Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                                url.startsWith("android-app:") -> Intent.parseUri(url, Intent.URI_ANDROID_APP_SCHEME)
                                                else -> Intent(Intent.ACTION_VIEW, uri)
                                            }
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            val pm = context.packageManager
                                            if (intent.resolveActivity(pm) != null) {
                                                context.startActivity(intent)
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to resolve external scheme: $url", e)
                                        }
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        canGoBack = view?.canGoBack() == true
                                        inspectUrl(url)
                                        checkCookies(cm) { header -> handleSuccess(header) }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        canGoBack = view?.canGoBack() == true

                                        // Inject CSS & modal dismissal to guarantee clean white visibility on Realme/ColorOS
                                        view?.evaluateJavascript("""
                                            (function() {
                                                var css = 'html, body { background: #FFFFFF !important; color: #000000 !important; } .vkid__root, .VKIDPanel { visibility: visible !important; opacity: 1 !important; }';
                                                var head = document.head || document.getElementsByTagName('head')[0];
                                                if (head) {
                                                    var style = document.createElement('style');
                                                    style.type = 'text/css';
                                                    style.appendChild(document.createTextNode(css));
                                                    head.appendChild(style);
                                                }
                                                var btns = document.querySelectorAll('button, span, div, a');
                                                for (var i = 0; i < btns.length; i++) {
                                                    var t = btns[i].innerText || btns[i].textContent;
                                                    if (t && (t.indexOf('Ввести данные вручную') !== -1 || t.indexOf('вручную') !== -1)) {
                                                        btns[i].click();
                                                        break;
                                                    }
                                                }
                                            })();
                                        """.trimIndent(), null)

                                        inspectUrl(url)
                                        checkCookies(cm) { header -> handleSuccess(header) }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            val scheme = request.url?.scheme?.lowercase()
                                            if (scheme != "http" && scheme != "https") return
                                            if (error?.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return

                                            val reqUrl = request.url?.toString() ?: ""
                                            if (reqUrl.contains("oauth.vk.ru")) {
                                                val fallbackUrl = reqUrl.replace("oauth.vk.ru", "oauth.vk.com")
                                                Log.w(TAG, "Failed loading $reqUrl, falling back to $fallbackUrl")
                                                view?.loadUrl(fallbackUrl)
                                                return
                                            }

                                            val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                            loadError = "$desc (код: ${error?.errorCode ?: -1})"
                                        }
                                    }
                                }

                                loadUrl("https://oauth.vk.ru/authorize?client_id=6287487&scope=calls&response_type=token")
                            }
                        }
                    )

                    // Continuous cookie polling while dialog is open
                    LaunchedEffect(Unit) {
                        val cm = CookieManager.getInstance()
                        while (isActive && !isDone) {
                            checkCookies(cm) { header -> handleSuccess(header) }
                            delay(1000)
                        }
                    }
                }
            }
        }
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

    val tokenPart = clean.substringAfter("access_token=")
    val token = tokenPart.substringBefore('&')
        .substringBefore('#')
        .substringBefore(';')
        .substringBefore('"')
        .substringBefore('\'')
        .trim()
    return if (token.isNotEmpty()) token else null
}

private fun checkCookies(cookieManager: CookieManager, onFound: (String) -> Unit) {
    val urls = listOf(
        "https://vk.ru",
        "https://vk.com",
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
