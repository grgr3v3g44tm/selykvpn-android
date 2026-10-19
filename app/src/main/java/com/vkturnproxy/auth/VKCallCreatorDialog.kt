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
import androidx.compose.material.icons.filled.ArrowBack
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        VkTurnTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
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
                                    imageVector = if (canGoBack) Icons.Default.ArrowBack else Icons.Default.Close,
                                    contentDescription = if (canGoBack) "Назад" else "Закрыть",
                                    tint = TextPrimary
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                errorMessage = null
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
                                    "Не удалось загрузить страницу",
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
                                            for (item in stored.cookieHeader.split(";")) {
                                                val part = item.trim()
                                                if (part.startsWith("remixsid=")) {
                                                    cm.setCookie(".vk.ru", "$part; path=/; domain=.vk.ru; Secure")
                                                    cm.setCookie(".vk.com", "$part; path=/; domain=.vk.com; Secure")
                                                } else if (part.startsWith("p=")) {
                                                    cm.setCookie(".login.vk.ru", "$part; path=/; domain=.login.vk.ru; Secure")
                                                    cm.setCookie(".login.vk.com", "$part; path=/; domain=.login.vk.com; Secure")
                                                }
                                            }
                                            cm.flush()
                                        }

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

                                                // Intercept OAuth completion
                                                if (url.contains("oauth.vk.ru/blank.html") || url.contains("oauth.vk.com/blank.html")) {
                                                    val fragment = Uri.parse(url).fragment
                                                    if (fragment != null && fragment.contains("access_token=")) {
                                                        val token = fragment.split("&")
                                                            .firstOrNull { it.startsWith("access_token=") }
                                                            ?.removePrefix("access_token=")
                                                        if (!token.isNullOrBlank()) {
                                                            isLoading = true
                                                            statusText = "Токен получен, вызываем calls.start…"

                                                            // Harvest cookie in background
                                                            harvestCookies(cm)

                                                            scope.launch {
                                                                createCallWithToken(token) { link, err ->
                                                                    if (link != null) {
                                                                        onCallCreated(link)
                                                                        onDismiss()
                                                                    } else {
                                                                        isLoading = false
                                                                        errorMessage = err ?: "Ошибка создания звонка"
                                                                    }
                                                                }
                                                            }
                                                            return true
                                                        }
                                                    }
                                                }

                                                // Allow standard web schemes to load inside WebView
                                                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript" || scheme == "data") {
                                                    return false
                                                }

                                                // Intercept ALL non-web schemes (intent:, android-app:, vk:, vkpay:, tel:, mailto:, etc.)
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
                                                    } else {
                                                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                        if (!fallbackUrl.isNullOrEmpty()) {
                                                            view?.loadUrl(fallbackUrl)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Intent scheme handling failed for: $url", e)
                                                }
                                                // Always return true so WebView never attempts to load non-http(s) schemes and never errors
                                                return true
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBack = view?.canGoBack() == true
                                                harvestCookies(cm)
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                harvestCookies(cm)
                                                if (url?.contains("id.vk.ru") == true || url?.contains("id.vk.com") == true) {
                                                    statusText = "Войдите или нажмите «Продолжить как…»"
                                                }
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

                                                    val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                                    errorMessage = "$desc (код: ${error?.errorCode ?: -1})"
                                                }
                                            }
                                        }

                                        loadUrl("https://oauth.vk.ru/authorize?client_id=$VK_OAUTH_CLIENT_ID&scope=calls&response_type=token&display=mobile")
                                    }
                                }
                            )

                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(BgDark.copy(alpha = 0.85f)),
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

private fun harvestCookies(cookieManager: CookieManager) {
    val urls = listOf("https://vk.ru", "https://vk.com", "https://login.vk.ru", "https://login.vk.com", "https://id.vk.ru", "https://id.vk.com")
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
            val endpoint = "https://$host/method/calls.start?v=5.276&access_token=$token"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val response = reader.readText()
            reader.close()

            val map = Gson().fromJson(response, Map::class.java)
            if (map.containsKey("error")) {
                val errMap = map["error"] as? Map<*, *>
                val msg = errMap?.get("error_msg") as? String ?: "VK error"
                lastError = msg
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
