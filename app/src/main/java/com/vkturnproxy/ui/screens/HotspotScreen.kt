package com.vkturnproxy.ui.screens

import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vkturnproxy.service.HttpProxyServer
import com.vkturnproxy.service.TetheringManager
import com.vkturnproxy.ui.theme.*
import com.vkturnproxy.utils.NetworkUtils
import kotlinx.coroutines.launch

enum class HotspotMode {
    ROOT_NAT,
    LOCAL_PROXY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotScreen(
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("vkturn_settings", Context.MODE_PRIVATE) }

    var isRootEnabled by remember { mutableStateOf(prefs.getBoolean("root_hotspot_enabled", false)) }
    var isRootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var selectedMode by remember { mutableStateOf(HotspotMode.ROOT_NAT) }

    val isProxyRunning by HttpProxyServer.isRunningFlow.collectAsState()
    var lanAddresses by remember { mutableStateOf(NetworkUtils.getHotspotAndLanAddresses()) }

    LaunchedEffect(Unit) {
        val root = TetheringManager.isRootAvailable()
        isRootAvailable = root
        if (!root) {
            selectedMode = HotspotMode.LOCAL_PROXY
        }
        lanAddresses = NetworkUtils.getHotspotAndLanAddresses()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Раздача интернета (Хотспот)", color = TextPrimary, fontWeight = FontWeight.Bold) },
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
            // Mode Selector Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = selectedMode == HotspotMode.ROOT_NAT,
                    onClick = {
                        if (isRootAvailable == true) {
                            selectedMode = HotspotMode.ROOT_NAT
                        } else {
                            Toast.makeText(context, "Root-доступ недоступен на этом устройстве", Toast.LENGTH_SHORT).show()
                        }
                    },
                    label = { Text("Прозрачный (Root)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryBlue,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = selectedMode == HotspotMode.LOCAL_PROXY,
                    onClick = { selectedMode = HotspotMode.LOCAL_PROXY },
                    label = { Text("Прокси (Без Root)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryBlue,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // Root Status Badge Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Статус Root (Magisk / su)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            when (isRootAvailable) {
                                true -> "Root-доступ предоставлен • Доступен прозрачный режим"
                                false -> "Root не обнаружен • Используется режим локального прокси"
                                null -> "Проверка прав…"
                            },
                            color = when (isRootAvailable) {
                                true -> AccentGreen
                                false -> AccentAmber
                                null -> TextSecondary
                            },
                            fontSize = 12.sp
                        )
                    }
                    Icon(
                        imageVector = when (isRootAvailable) {
                            true -> Icons.Default.CheckCircle
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = when (isRootAvailable) {
                            true -> AccentGreen
                            else -> AccentAmber
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (selectedMode == HotspotMode.ROOT_NAT) {
                // ROOT Transparent NAT Card
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
                                Icon(
                                    imageVector = Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    tint = if (isRootEnabled) AccentGreen else TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Прозрачная раздача через iptables", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        if (isRootEnabled) "iptables NAT активен" else "Выключено",
                                        color = if (isRootEnabled) AccentGreen else TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isRootEnabled,
                                onCheckedChange = {
                                    isRootEnabled = it
                                    prefs.edit().putBoolean("root_hotspot_enabled", it).apply()
                                    TetheringManager.applyRules(it)
                                    onChanged()
                                }
                            )
                        }

                        HorizontalDivider(color = SurfaceVariantDark)

                        Text(
                            "Как это работает в Root-режиме:",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "• Точка доступа Wi-Fi (ap+) и USB-модем (rndis+) автоматически заворачиваются через туннель tun0 с помощью iptables NAT MASQUERADE.\n\n" +
                            "• DNS перенаправляется на защищенные серверы.\n\n" +
                            "• Подключенным ТВ, ноутбукам или приставкам не требуется настраивать прокси — весь интернет сразу работает через VPN!",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    TetheringManager.applyRules(isRootEnabled)
                                    Toast.makeText(context, "Правила iptables обновлены", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Применить правила iptables повторно", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                // NON-ROOT LAN HTTP PROXY Card
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = if (isProxyRunning) AccentGreen else TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("LAN HTTP Прокси-сервер", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        if (isProxyRunning) "Слушает 0.0.0.0:8080" else "Остановлен",
                                        color = if (isProxyRunning) AccentGreen else TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isProxyRunning,
                                onCheckedChange = { start ->
                                    if (start) {
                                        val ok = HttpProxyServer.start(8080)
                                        if (ok) {
                                            Toast.makeText(context, "LAN Прокси запущен на порту 8080", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Не удалось открыть порт 8080", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        HttpProxyServer.stop()
                                        Toast.makeText(context, "LAN Прокси остановлен", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        HorizontalDivider(color = SurfaceVariantDark)

                        // Address details
                        val primaryIp = lanAddresses.firstOrNull()?.ipAddress ?: "192.168.43.1"
                        Text("Адрес для подключения клиентов:", color = TextSecondary, fontSize = 12.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // IP Box
                            OutlinedCard(
                                modifier = Modifier.weight(1.5f),
                                colors = CardDefaults.outlinedCardColors(containerColor = SurfaceVariantDark)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("IP адрес", color = TextSecondary, fontSize = 11.sp)
                                        Text(primaryIp, color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(primaryIp))
                                            Toast.makeText(context, "IP $primaryIp скопирован", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Port Box
                            OutlinedCard(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.outlinedCardColors(containerColor = SurfaceVariantDark)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Порт", color = TextSecondary, fontSize = 11.sp)
                                        Text("8080", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString("8080"))
                                            Toast.makeText(context, "Порт 8080 скопирован", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        // Instructions
                        Text(
                            "Инструкция по настройке подключаемых устройств:",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "1. Включите Точку доступа Wi-Fi или USB-модем в системных настройках телефона.\n\n" +
                            "2. Подключите ноутбук, ТВ или другое устройство к этой Wi-Fi сети.\n\n" +
                            "3. В свойствах Wi-Fi на подключаемом устройстве укажите прокси:\n" +
                            "   • IP: $primaryIp\n" +
                            "   • Порт: 8080\n" +
                            "   • Тип: HTTP / HTTPS\n\n" +
                            "4. Весь трафик устройства сразу пойдет через зашифрованный туннель VK TURN!",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
