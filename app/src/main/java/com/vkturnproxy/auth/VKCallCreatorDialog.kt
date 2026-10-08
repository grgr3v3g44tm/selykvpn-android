package com.vkturnproxy.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BgDark
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Создание звонка VK", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
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

                if (errorMessage != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text(errorMessage!!, color = AccentRed, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onDismiss) {
                                Text("Закрыть")
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    // Plant stored cookies
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
                                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.contains("oauth.vk.ru/blank.html") || url.contains("oauth.vk.com/blank.html")) {
                                                val fragment = Uri.parse(url).fragment
                                                if (fragment != null && fragment.contains("access_token=")) {
                                                    val token = fragment.split("&")
                                                        .firstOrNull { it.startsWith("access_token=") }
                                                        ?.removePrefix("access_token=")
                                                    if (!token.isNullOrBlank()) {
                                                        isLoading = true
                                                        statusText = "Токен получен, вызываем calls.start…"
                                                        scope.launch {
                                                            createCallWithToken(token) { link, err ->
                                                                if (link != null) {
                                                                    onCallCreated(link)
                                                                    onDismiss()
                                                                } else {
                                                                    errorMessage = err ?: "Ошибка создания звонка"
                                                                }
                                                            }
                                                        }
                                                        return true
                                                    }
                                                }
                                            }
                                            return false
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            if (url?.contains("id.vk.ru") == true) {
                                                statusText = "Нажмите «Продолжить как…» для создания постоянного звонка"
                                            }
                                        }
                                    }

                                    loadUrl("https://oauth.vk.ru/authorize?client_id=$VK_OAUTH_CLIENT_ID&scope=calls&response_type=token")
                                }
                            }
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(BgDark.copy(alpha = 0.8f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = PrimaryBlue)
                            }
                        }
                    }
                }
            }
        }
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
