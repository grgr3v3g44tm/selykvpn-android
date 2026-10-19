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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                                    imageVector = if (canGoBack) Icons.Default.ArrowBack else Icons.Default.Close,
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
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewInstance = this
                                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
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
                                                    Log.w(TAG, "Failed to resolve external scheme: $url", e)
                                                }
                                                // Always return true so WebView never attempts to load non-http(s) schemes and never errors
                                                return true
                                            }

                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBack = view?.canGoBack() == true
                                                checkCookies(cm) { header -> handleSuccess(header) }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                // Dismiss "Вход без пароля" / "Войти через приложение" popup in favor of manual entry fields
                                                view?.evaluateJavascript("""
                                                    (function() {
                                                        var btns = document.querySelectorAll('button, span, div, a');
                                                        for (var i = 0; i < btns.length; i++) {
                                                            var t = btns[i].innerText || btns[i].textContent;
                                                            if (t && (t.indexOf('Ввести данные вручную') !== -1 || t.indexOf('вручную') !== -1)) {
                                                                btns[i].click();
                                                                return;
                                                            }
                                                        }
                                                        var closeBtn = document.querySelector('[aria-label="Закрыть"], .vkuiModalDismissButton, [data-testid="box-layout__close-button"], [data-test-id="modal-close-button"]');
                                                        if (closeBtn) {
                                                            closeBtn.click();
                                                        }
                                                    })();
                                                """.trimIndent(), null)
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
                                                    if (reqUrl.contains("vk.com")) {
                                                        val fallbackUrl = reqUrl.replace("vk.com", "vk.ru")
                                                        Log.w(TAG, "Failed loading $reqUrl (error ${error?.errorCode}), falling back to $fallbackUrl")
                                                        view?.loadUrl(fallbackUrl)
                                                        return
                                                    }

                                                    val desc = error?.description?.toString() ?: "Сетевая ошибка"
                                                    loadError = "$desc (код: ${error?.errorCode ?: -1})"
                                                }
                                            }
                                        }

                                        loadUrl("https://vk.com/login")
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
    }
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

    if (!remixsid.isNullOrBlank() && !p.isNullOrBlank()) {
        val header = "remixsid=$remixsid; p=$p"
        onFound(header)
    }
}
