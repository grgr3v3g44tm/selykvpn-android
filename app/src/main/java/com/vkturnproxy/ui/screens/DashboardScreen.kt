package com.vkturnproxy.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vkturnproxy.BuildConfig
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.model.TunnelStats
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.theme.*
import com.vkturnproxy.update.UpdateInfo
import com.vkturnproxy.update.UpdateState
import java.io.File

@Composable
fun DashboardScreen(
    status: VpnStatus,
    stats: TunnelStats,
    activeServer: ServerConfig?,
    isRootHotspotEnabled: Boolean,
    splitTunnelAppCount: Int,
    updateState: UpdateState = UpdateState.Idle,
    onToggleVpn: () -> Unit,
    onSelectServerClicked: () -> Unit,
    onHotspotClicked: () -> Unit,
    onSplitTunnelClicked: () -> Unit,
    onSpeedTestClicked: () -> Unit,
    onCheckUpdateClicked: () -> Unit = {},
    onDownloadUpdateClicked: (UpdateInfo) -> Unit = {},
    onInstallApkClicked: (File) -> Unit = {},
    onDismissUpdateClicked: () -> Unit = {}
) {
    val isConnected = status == VpnStatus.CONNECTED
    val isConnecting = status == VpnStatus.CONNECTING || status == VpnStatus.WAITING_TURN

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnecting) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> AccentGreen
            isConnecting -> AccentAmber
            status == VpnStatus.ERROR -> AccentRed
            else -> PrimaryBlue
        },
        label = "buttonColor"
    )

    val availableUpdate = (updateState as? UpdateState.Available)?.info
        ?: (updateState as? UpdateState.Downloading)?.info
        ?: (updateState as? UpdateState.Downloaded)?.info

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Title + Version + Update Check Icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "VK TURN",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceVariantDark
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCheckUpdateClicked,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (availableUpdate != null) Icons.Default.SystemUpdateAlt else Icons.Default.Sync,
                        contentDescription = "Проверить обновления",
                        tint = if (availableUpdate != null) AccentGreen else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Update Available Notice Banner
            if (updateState is UpdateState.Available) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { onDownloadUpdateClicked(updateState.info) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.15f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Доступно обновление v${updateState.info.versionName}",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = "Обновить",
                            color = PrimaryBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Top Server Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectServerClicked() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SurfaceVariantDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VpnLock,
                                contentDescription = null,
                                tint = PrimaryBlue
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = activeServer?.name ?: "Сервер не выбран",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "${activeServer?.mode?.name ?: "SRTP"} • ${activeServer?.numConnections ?: 30} conns",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }
        }

        // Center Connect Button & Status
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(175.dp)
                    .scale(if (isConnecting) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(buttonColor.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
                    .border(3.dp, buttonColor.copy(alpha = 0.8f), CircleShape)
                    .clickable { onToggleVpn() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(135.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(SurfaceVariantDark, SurfaceDark)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Connect",
                        tint = buttonColor,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            val statusText = when (status) {
                VpnStatus.DISCONNECTED -> "ОТКЛЮЧЕНО"
                VpnStatus.CONNECTING -> "ИНИЦИАЛИЗАЦИЯ VK…"
                VpnStatus.WAITING_TURN -> "ОЖИДАНИЕ TURN…"
                VpnStatus.CONNECTED -> "ПОДКЛЮЧЕНО"
                VpnStatus.ERROR -> "ОШИБКА ПОДКЛЮЧЕНИЯ"
            }

            Text(
                text = statusText,
                color = buttonColor,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                letterSpacing = 1.2.sp
            )

            if (stats.turnServer.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Relay: ${stats.turnServer}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            if (isConnected) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onSpeedTestClicked,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Замерить скорость (Ookla)", fontSize = 12.sp)
                }
            }
        }

        // Stats Cards & Quick Toggles
        Column(modifier = Modifier.fillMaxWidth()) {
            // Speed Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Входящий", color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = formatSpeed(stats.speedRxBps),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Всего: ${formatBytes(stats.bytesRx)}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Исходящий", color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = formatSpeed(stats.speedTxBps),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Всего: ${formatBytes(stats.bytesTx)}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick toggles row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onHotspotClicked() },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRootHotspotEnabled) SurfaceVariantDark else SurfaceDark
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = if (isRootHotspotEnabled) AccentGreen else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Раздача Root", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (isRootHotspotEnabled) "Включена" else "Выключена",
                                color = if (isRootHotspotEnabled) AccentGreen else TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSplitTunnelClicked() },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Исключения", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (splitTunnelAppCount > 0) "$splitTunnelAppCount прилож." else "Все в VPN",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(56.dp))
    }

    // Modal Update Dialog
    UpdateDialog(
        state = updateState,
        onDownload = onDownloadUpdateClicked,
        onInstall = onInstallApkClicked,
        onDismiss = onDismissUpdateClicked
    )
}

@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = SurfaceDark,
                title = { Text("Обновление", color = TextPrimary) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(24.dp))
                        Text("Проверка наличия обновлений…", color = TextSecondary, fontSize = 14.sp)
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = SurfaceDark,
                title = {
                    Text("Доступно обновление v${state.info.versionName}", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (state.info.fileSize > 0) {
                            Text(
                                text = "Размер: ${formatBytes(state.info.fileSize)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = "Что нового:",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.info.changelog,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onDownload(state.info) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text("Скачать и обновить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Позже", color = TextSecondary)
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {}, // prevent dismissing mid-download
                containerColor = SurfaceDark,
                title = {
                    Text("Загрузка обновления…", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = PrimaryBlue,
                            trackColor = SurfaceVariantDark
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.Downloaded -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = SurfaceDark,
                title = {
                    Text("Обновление готово к установке", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Файл обновления успешно загружен. Нажмите кнопку ниже для запуска системной установки.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { onInstall(state.apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Установить сейчас")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", color = TextSecondary)
                    }
                }
            )
        }

        is UpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = SurfaceDark,
                title = { Text("Обновлений не найдено", color = TextPrimary) },
                text = {
                    Text(
                        text = "У вас установлена самая актуальная версия v${BuildConfig.VERSION_NAME}.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text("Понятно")
                    }
                }
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = SurfaceDark,
                title = { Text("Ошибка обновления", color = AccentRed) },
                text = {
                    Text(
                        text = state.message,
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                    ) {
                        Text("Закрыть", color = TextPrimary)
                    }
                }
            )
        }

        UpdateState.Idle -> {
            // Nothing to render
        }
    }
}

fun formatSpeed(bps: Long): String {
    val bytesPerSec = bps / 8.0
    return when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576)
        bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024)
        else -> "${bytesPerSec.toInt()} B/s"
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
