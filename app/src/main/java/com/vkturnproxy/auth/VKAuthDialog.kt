package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VKAuthDialog(
    onDismiss: () -> Unit,
    onSuccess: (cookieHeader: String) -> Unit
) {
    var statusText by remember { mutableStateOf("Войдите в аккаунт VK. Поддерживается 2FA и SMS.") }
    var isDone by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewInstance?.apply {
                    stopLoading()
                    clearHistory()
                    clearCache(true)
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BgDark
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = {
                        Column {
                            Text("Вход в VK (Cookie Auth)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(statusText, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                )

                // WebView Container
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        checkCookies(cm) { header ->
                                            if (!isDone) {
                                                isDone = true
                                                val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
                                                VKCookieStore.save(header, expiry)
                                                VKCookieStore.isEnabled = true
                                                onSuccess(header)
                                                onDismiss()
                                            }
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        checkCookies(cm) { header ->
                                            if (!isDone) {
                                                isDone = true
                                                val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
                                                VKCookieStore.save(header, expiry)
                                                VKCookieStore.isEnabled = true
                                                onSuccess(header)
                                                onDismiss()
                                            }
                                        }
                                    }
                                }

                                loadUrl("https://vk.ru/")
                            }
                        }
                    )

                    // Continuous cookie polling while dialog is open
                    LaunchedEffect(Unit) {
                        val cm = CookieManager.getInstance()
                        while (isActive && !isDone) {
                            checkCookies(cm) { header ->
                                if (!isDone) {
                                    isDone = true
                                    val expiry = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
                                    VKCookieStore.save(header, expiry)
                                    VKCookieStore.isEnabled = true
                                    onSuccess(header)
                                    onDismiss()
                                }
                            }
                            delay(1000)
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
                if (name == "remixsid" && value.isNotEmpty()) {
                    remixsid = value
                }
                if (name == "p" && value.isNotEmpty()) {
                    p = value
                }
            }
        }
    }

    if (remixsid != null && p != null) {
        onFound("remixsid=$remixsid; p=$p")
    }
}
