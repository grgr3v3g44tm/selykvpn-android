package com.vkturnproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.vkturnproxy.auth.VKAuthDialog
import com.vkturnproxy.auth.VKCallCreatorDialog
import com.vkturnproxy.auth.VKCookieStore
import com.vkturnproxy.model.ProxyMode
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.model.hasValidVKCallLink
import com.vkturnproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    server: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var name by remember { mutableStateOf(server.name) }
    var mode by remember { mutableStateOf(server.mode) }
    var vkLink by remember { mutableStateOf(server.vkLink) }
    var peerAddress by remember { mutableStateOf(server.peerAddress) }
    var numConnections by remember { mutableStateOf(server.numConnections.toFloat()) }
    var useUDP by remember { mutableStateOf(server.useUDP) }
    var wrapKeyHex by remember { mutableStateOf(server.wrapKeyHex) }
    var serverPassword by remember { mutableStateOf(server.serverPassword) }
    var deviceId by remember { mutableStateOf(server.deviceId) }

    // VK Cookie Auth state
    var useCookieAuth by remember { mutableStateOf(server.useCookieAuth || VKCookieStore.isEnabled) }
    var hasStoredCookie by remember { mutableStateOf(VKCookieStore.hasValidCookie()) }
    var cookieExpiryText by remember { mutableStateOf(VKCookieStore.getFormattedExpiry()) }
    var showVKAuthDialog by remember { mutableStateOf(false) }
    var showVKCallCreatorDialog by remember { mutableStateOf(false) }

    // Advanced tuning parameters
    var uplinkPaceKiB by remember { mutableStateOf(server.uplinkPaceKiB.toString()) }
    var uplinkPaceBurstKiB by remember { mutableStateOf(server.uplinkPaceBurstKiB.toString()) }
    var forceLegacyCaptcha by remember { mutableStateOf(server.forceLegacyCaptcha) }
    var memstatsFastTicks by remember { mutableStateOf(server.memstatsFastTicks) }

    // WireGuard
    var privateKey by remember { mutableStateOf(server.privateKey) }
    var peerPublicKey by remember { mutableStateOf(server.peerPublicKey) }
    var presharedKey by remember { mutableStateOf(server.presharedKey) }
    var tunnelAddress by remember { mutableStateOf(server.tunnelAddress) }
    var dnsServers by remember { mutableStateOf(server.dnsServers) }
    var mtu by remember { mutableStateOf(server.mtu.toString()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
            TopAppBar(
                title = { Text("Настройки сервера", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val settingsMap = mutableMapOf<String, Any>(
                            "serverName" to name,
                            "peerAddress" to peerAddress,
                            "peerPublicKey" to peerPublicKey,
                            "privateKey" to privateKey,
                            "presharedKey" to presharedKey,
                            "tunnelAddress" to tunnelAddress,
                            "dnsServers" to dnsServers,
                            "numConnections" to numConnections.toInt(),
                            "useDTLS" to true,
                            "useSrtp" to (mode == ProxyMode.SRTP || mode == ProxyMode.SRTP_WRAP || mode == ProxyMode.SRTP_WRAP_A || mode == ProxyMode.SRTP_WRAP_S),
                            "useUDP" to useUDP,
                            "useWrap" to (mode == ProxyMode.SRTP_WRAP || mode == ProxyMode.SRTP_WRAP_S),
                            "useWrapA" to (mode == ProxyMode.SRTP_WRAP_A),
                            "vkLink" to vkLink,
                            "wrapKeyHex" to wrapKeyHex,
                            "serverPassword" to serverPassword,
                            "deviceId" to deviceId,
                            "mtu" to (mtu.toIntOrNull() ?: 1420)
                        )
                        val rootMap = mapOf(
                            "type" to "connection",
                            "version" to 1,
                            "settings" to settingsMap
                        )
                        val jsonString = com.google.gson.Gson().toJson(rootMap)
                        val b64 = android.util.Base64.encodeToString(jsonString.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        val universalUrl = "vkturnproxy://import?data=$b64"
                        clipboardManager.setText(AnnotatedString(universalUrl))
                        Toast.makeText(context, "Универсальная ссылка (iOS + Android) скопирована!", Toast.LENGTH_LONG).show()
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = TextPrimary)
                    }

                    IconButton(onClick = {
                        val updated = server.copy(
                            name = name,
                            mode = mode,
                            vkLink = vkLink,
                            // Флаг «звонок ещё не создан» снимается, как только
                            // в поле появилась настоящая ссылка на звонок.
                            needsCallLink = server.needsCallLink && !hasValidVKCallLink(vkLink),
                            peerAddress = peerAddress,
                            numConnections = numConnections.toInt(),
                            useUDP = useUDP,
                            wrapKeyHex = wrapKeyHex,
                            serverPassword = serverPassword,
                            deviceId = deviceId,
                            useCookieAuth = useCookieAuth,
                            uplinkPaceKiB = uplinkPaceKiB.toIntOrNull() ?: 0,
                            uplinkPaceBurstKiB = uplinkPaceBurstKiB.toIntOrNull() ?: 16,
                            forceLegacyCaptcha = forceLegacyCaptcha,
                            memstatsFastTicks = memstatsFastTicks,
                            privateKey = privateKey,
                            peerPublicKey = peerPublicKey,
                            presharedKey = presharedKey,
                            tunnelAddress = tunnelAddress,
                            dnsServers = dnsServers,
                            mtu = mtu.toIntOrNull() ?: 1420
                        )
                        onSave(updated)
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Сохранить", tint = PrimaryBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // General info card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Основные параметры", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Имя сервера") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Режим протокола", color = TextSecondary, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(ProxyMode.SRTP, ProxyMode.SRTP_WRAP, ProxyMode.SRTP_WRAP_A).forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryBlue,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = peerAddress,
                        onValueChange = { peerAddress = it },
                        label = { Text("Адрес прокси (IP:Port)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("example.com:56000") }
                    )

                    Column {
                        Text(
                            "Количество соединений: ${numConnections.toInt()}",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = numConnections,
                            onValueChange = { numConnections = it },
                            valueRange = 1f..60f,
                            steps = 59
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Использовать UDP транспорт", color = TextPrimary, fontSize = 14.sp)
                        Switch(checked = useUDP, onCheckedChange = { useUDP = it })
                    }
                }
            }

            // VK Call Links & Multiline Relay Aggregation
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ссылки на звонки VK", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val validLinksCount = vkLink.lines().map { it.trim() }.filter { it.contains("call/join/") }.size
                            if (validLinksCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(containerColor = if (validLinksCount > 1) AccentGreen else SurfaceVariantDark) {
                                    Text(
                                        "$validLinksCount звон. → ${validLinksCount * 2} релея",
                                        color = if (validLinksCount > 1) Color.Black else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { showVKCallCreatorDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Создать звонок", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = vkLink,
                        onValueChange = { vkLink = it },
                        label = { Text("VK Call Link (ссылка или список ссылок)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text("https://vk.ru/call/join/...\nhttps://vk.ru/call/join/...") }
                    )

                    if (!hasValidVKCallLink(vkLink)) {
                        Text(
                            "⚠️ Ссылка на звонок обязательна для подключения к VPN. Нажмите кнопку «+ Создать звонок» выше для автоматического создания.",
                            color = AccentRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        "Каждая ссылка с новой строки добавляет 2 независимых TURN релея. Нагрузка равномерно распределяется между ними для максимальной стабильности.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // VK Account Cookie Auth Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Авторизация VK (Cookie Auth)", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Switch(
                            checked = useCookieAuth,
                            onCheckedChange = {
                                useCookieAuth = it
                                VKCookieStore.isEnabled = it
                            }
                        )
                    }

                    Text(
                        "Использование сессионных куки аккаунта (remixsid + p) снимает анонимные лимиты, защищает от капч и позволяет создавать постоянные звонки через VK API.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Divider(color = SurfaceVariantDark, thickness = 1.dp)

                    // Session Status
                    if (hasStoredCookie) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Сессия VK активна", color = AccentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Истекает: $cookieExpiryText", color = TextSecondary, fontSize = 11.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showVKCallCreatorDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Создать звонок API", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    VKCookieStore.clearCookies()
                                    hasStoredCookie = false
                                    useCookieAuth = false
                                }
                            ) {
                                Text("Выйти", color = AccentRed, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Вход в аккаунт VK не выполнен", color = AccentAmber, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { showVKAuthDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Войти в VK (через браузер)", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Mode-specific options
            if (mode == ProxyMode.SRTP_WRAP || mode == ProxyMode.SRTP_WRAP_S) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Настройки WRAP", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = wrapKeyHex,
                            onValueChange = { wrapKeyHex = it },
                            label = { Text("WRAP Key (Hex)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (mode == ProxyMode.SRTP_WRAP_A) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Авторизация сервера", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = serverPassword,
                            onValueChange = { serverPassword = it },
                            label = { Text("Пароль сервера") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { deviceId = it },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // WireGuard settings
            if (mode != ProxyMode.SRTP_WRAP_A) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Параметры WireGuard", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = { Text("Private Key (клиента)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = peerPublicKey,
                            onValueChange = { peerPublicKey = it },
                            label = { Text("Peer Public Key (сервера)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = presharedKey,
                            onValueChange = { presharedKey = it },
                            label = { Text("Preshared Key (опционально)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = tunnelAddress,
                            onValueChange = { tunnelAddress = it },
                            label = { Text("Tunnel Address (IP/маска)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = dnsServers,
                            onValueChange = { dnsServers = it },
                            label = { Text("DNS Servers") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = mtu,
                            onValueChange = { mtu = it },
                            label = { Text("MTU (обычно 1420)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Advanced Settings (iOS VKTurnProxy exact parity)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Продвинутые параметры (Advanced)", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Uplink Pacing
                    OutlinedTextField(
                        value = uplinkPaceKiB,
                        onValueChange = { uplinkPaceKiB = it.filter { c -> c.isDigit() } },
                        label = { Text("Uplink Pacing (KiB/s)") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("0 = выключено. 500-2500 KiB/s защищает от троттлинга и дропов пакетов ТСПУ.")
                        }
                    )

                    OutlinedTextField(
                        value = uplinkPaceBurstKiB,
                        onValueChange = { uplinkPaceBurstKiB = it.filter { c -> c.isDigit() } },
                        label = { Text("Pacing Burst (KiB)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Принудительная Legacy Captcha", color = TextPrimary, fontSize = 14.sp)
                            Text("Классическая графическая капча VK", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = forceLegacyCaptcha,
                            onCheckedChange = { forceLegacyCaptcha = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Быстрый опрос памяти Go core", color = TextPrimary, fontSize = 14.sp)
                            Text("Сбор статистики аллокаций памяти каждую 1 сек.", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = memstatsFastTicks,
                            onCheckedChange = { memstatsFastTicks = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showVKAuthDialog) {
        VKAuthDialog(
            onDismiss = { showVKAuthDialog = false },
            onSuccess = {
                hasStoredCookie = true
                cookieExpiryText = VKCookieStore.getFormattedExpiry()
                useCookieAuth = true
                showVKAuthDialog = false
            }
        )
    }

    if (showVKCallCreatorDialog) {
        VKCallCreatorDialog(
            onDismiss = { showVKCallCreatorDialog = false },
            onCallCreated = { newLink ->
                // Заглушка «vkturnproxy://placeholder» выбрасывается: она не является
                // ссылкой на звонок, и её нельзя оставлять в списке релеев.
                val rest = vkLink.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals(ServerConfig.PLACEHOLDER_CALL_LINK, ignoreCase = true) }
                vkLink = (listOf(newLink.trim()) + rest).joinToString("\n")
                showVKCallCreatorDialog = false
            }
        )
    }
}
}

