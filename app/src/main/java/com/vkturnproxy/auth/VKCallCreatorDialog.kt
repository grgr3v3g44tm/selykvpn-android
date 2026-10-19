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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
private const val VK_OAUTH_AUTHORIZE_URL = "https://oauth.vk.ru/authorize?client_id=$VK_OAUTH_CLIENT_ID&scope=calls&response_type=token"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VKCallCreatorDialog(
    onDismiss: () -> Unit,
    onCallCreated: (joinLink: String) -> Unit
) {
    var hasLogin by remember { mutableStateOf(VKCookieStore.hasValidLogin()) }
    var isLoggingIn by remember { mutableStateOf(false) }

    var statusText by remember { mutableStateOf("Подтвердите аккаунт для создания звонка…") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var isTokenHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        VkTurnTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BgDark
            ) {
                // 1. GATE STATE: User is not logged into VK yet
                if (!hasLogin && !isLoggingIn) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(PrimaryBlue.copy(alpha = 0.15f), shape = RoundedCornerShape(32.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    "Требуется вход в аккаунт VK",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    "Для создания постоянной ссылки на звонок необходима авторизация в VK. Ссылка создается через официальный API VK от вашего имени и не отключается после выхода участников, в отличие от ссылок из браузера.",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = { isLoggingIn = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Войти в аккаунт VK", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Отмена", color = TextSecondary)
                                }
                            }
                        }
                    }
                } else if (isLoggingIn) {
                    // 2. LOGIN WEBVIEW: User performs regular VK login
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        "Вход в аккаунт VK",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        "Войдите в аккаунт (поддерживается 2FA)",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { isLoggingIn = false }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Назад",
                                        tint = TextPrimary
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = TextPrimary
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                        )

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

                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.parseColor("#0B0F19"))
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
                                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                webProgress = newProgress
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

                                                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                    return false
                                                }

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
                                                } catch (_: Exception) {}
                                                return true
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                // Dismiss "Вход без пароля" popup in favor of manual entry fields
                                                view?.evaluateJavascript("""
                                                    (function() {
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

                                                checkLoginCookies(cm) { header ->
                                                    VKCookieStore.save(header, System.currentTimeMillis() + 365L * 86400 * 1000)
                                                    VKCookieStore.isEnabled = true
                                                    hasLogin = true
                                                    isLoggingIn = false
                                                }
                                            }
                                        }

                                        loadUrl("https://m.vk.com/login?forced_color_scheme_mode=dark")
                                    }
                                }
                            )

                            // Polling watcher for login cookies
                            LaunchedEffect(Unit) {
                                val cm = CookieManager.getInstance()
                                while (isActive && !hasLogin) {
                                    checkLoginCookies(cm) { header ->
                                        VKCookieStore.save(header, System.currentTimeMillis() + 365L * 86400 * 1000)
                                        VKCookieStore.isEnabled = true
                                        hasLogin = true
                                        isLoggingIn = false
                                    }
                                    delay(1000)
                                }
                            }
                        }
                    }
                } else {
                    // 3. OAUTH CALL CREATOR WEBVIEW: Plants cookies and loads oauth.vk.ru/authorize
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
                                TextButton(onClick = {
                                    VKCookieStore.clearCookies()
                                    hasLogin = false
                                    isLoggingIn = true
                                }) {
                                    Text("Сменить аккаунт", color = PrimaryBlue, fontSize = 12.sp)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                        )

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
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
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

                                                    if (isOAuthBlankPage(url) || url.contains("access_token=")) {
                                                        isLoading = true
                                                        statusText = "Авторизация подтверждена, создание звонка…"
                                                        val token = extractAccessToken(url)
                                                        if (token != null) {
                                                            handleTokenFound(token, view)
                                                            return true
                                                        }
                                                        return false
                                                    }

                                                    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                        return false
                                                    }

                                                    return true
                                                }

                                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                    super.onPageStarted(view, url, favicon)
                                                    canGoBack = view?.canGoBack() == true
                                                    if (isOAuthBlankPage(url) || url?.contains("access_token=") == true) {
                                                        isLoading = true
                                                        statusText = "Создаем звонок через VK API…"
                                                    }
                                                    inspectUrl(url, view)
                                                    inspectUrl(view?.url, view)
                                                }

                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    canGoBack = view?.canGoBack() == true
                                                    if (isOAuthBlankPage(url) || url?.contains("access_token=") == true) {
                                                        isLoading = true
                                                        statusText = "Создаем звонок через VK API…"
                                                    }
                                                    inspectUrl(url, view)
                                                    inspectUrl(view?.url, view)

                                                    view?.evaluateJavascript("window.location.href") { jsUrl ->
                                                        inspectUrl(jsUrl, view)
                                                    }

                                                    if (url?.contains("id.vk.ru") == true || url?.contains("id.vk.com") == true) {
                                                        statusText = "Нажмите «Продолжить как…» для создания звонка"
                                                    } else if (url?.contains("oauth.vk.ru") == true || url?.contains("oauth.vk.com") == true) {
                                                        statusText = "Подтвердите доступ к звонкам"
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

                                            // Plant stored cookies with exact domain mapping from iOS VKTurnProxy
                                            val stored = VKCookieStore.load()
                                            if (stored != null) {
                                                plantCookiesAndLoad(this, cm, stored.cookieHeader)
                                            } else {
                                                loadUrl(VK_OAUTH_AUTHORIZE_URL)
                                            }
                                        }
                                    }
                                )

                                // Background watcher for OAuth redirect hash and token
                                LaunchedEffect(Unit) {
                                    while (isActive && !isTokenHandled) {
                                        webViewInstance?.let { wv ->
                                            inspectUrl(wv.url, wv)
                                            wv.evaluateJavascript("window.location.href") { jsUrl ->
                                                inspectUrl(jsUrl, wv)
                                            }
                                        }
                                        delay(200)
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
}

/**
 * Plants cookies into CookieManager using the exact per-domain rules discovered in iOS VKTurnProxy:
 * - remixsid is issued on .vk.ru
 * - p is issued on .login.vk.ru
 * Putting both on one domain causes VK's act=connect_internal gate to see neither!
 */
private fun plantCookiesAndLoad(webView: WebView, cookieManager: CookieManager, cookieHeader: String) {
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

    // Ephemeral clean start: clear old cookies, then plant the harvested session
    cookieManager.removeAllCookies {
        if (!remixsid.isNullOrBlank()) {
            cookieManager.setCookie("https://vk.ru/", "remixsid=$remixsid; Domain=.vk.ru; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://vk.com/", "remixsid=$remixsid; Domain=.vk.com; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://oauth.vk.ru/", "remixsid=$remixsid; Domain=.vk.ru; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://id.vk.ru/", "remixsid=$remixsid; Domain=.vk.ru; Path=/; Secure; SameSite=None")
        }

        if (!p.isNullOrBlank()) {
            cookieManager.setCookie("https://login.vk.ru/", "p=$p; Domain=.login.vk.ru; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://login.vk.com/", "p=$p; Domain=.login.vk.com; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://oauth.vk.ru/", "p=$p; Domain=.login.vk.ru; Path=/; Secure; SameSite=None")
            cookieManager.setCookie("https://id.vk.ru/", "p=$p; Domain=.login.vk.ru; Path=/; Secure; SameSite=None")
        }

        cookieManager.flush()
        webView.loadUrl(VK_OAUTH_AUTHORIZE_URL)
    }
}

private fun checkLoginCookies(cookieManager: CookieManager, onFound: (String) -> Unit) {
    val urls = listOf(
        "https://vk.ru",
        "https://vk.com",
        "https://login.vk.ru",
        "https://login.vk.com",
        "https://id.vk.ru",
        "https://id.vk.com"
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

    if (!remixsid.isNullOrBlank() && !p.isNullOrBlank()) {
        val header = "remixsid=$remixsid; p=$p"
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

/**
 * Creates a call using the freshly-obtained user OAuth token via calls.start,
 * matching iOS VKCallsAPI.swift GET method.
 */
private suspend fun createCallWithToken(
    token: String,
    callback: (link: String?, error: String?) -> Unit
) = withContext(Dispatchers.IO) {
    val hosts = listOf("api.vk.ru", "api.vk.com")
    var lastError = "Не удалось связаться с серверами VK"

    for (host in hosts) {
        try {
            val endpoint = "https://$host/method/calls.start?v=5.276&access_token=$token"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "VKAndroidApp/8.0 (Android 14)")
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
                lastError = "$msg (код: $code)"
                Log.w(TAG, "calls.start returned VK error: $lastError")
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
            lastError = e.message ?: "Сетевая ошибка"
        }
    }

    withContext(Dispatchers.Main) {
        callback(null, lastError)
    }
}
