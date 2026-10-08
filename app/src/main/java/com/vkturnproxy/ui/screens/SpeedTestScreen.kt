package com.vkturnproxy.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vkturnproxy.core.WgTurnCore
import com.vkturnproxy.service.VkTurnVpnService
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SpeedTestScreen"

data class SpeedTestServer(
    val id: String = "",
    val name: String = "",
    val sponsor: String = "",
    val country: String = "",
    val host: String = "",
    @SerializedName("distance_km") val distanceKm: Double = 0.0,
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

data class SpeedTestPhase(
    @SerializedName("library_mbps") val libraryMbps: Double = 0.0,
    @SerializedName("raw_mbps") val rawMbps: Double = 0.0,
    val bytes: Long = 0L,
    @SerializedName("actual_sec") val actualSec: Double = 0.0
)

data class SpeedTestProgress(
    val state: String = "idle", // "idle", "running", "done", "error"
    val stage: String = "",
    val error: String? = null,
    @SerializedName("server_id") val serverId: String = "",
    @SerializedName("server_desc") val serverDesc: String = "",
    @SerializedName("ookla_sees_ip") val ooklaSeesIp: String = "",
    @SerializedName("ookla_sees_isp") val ooklaSeesIsp: String = "",
    @SerializedName("ping_ms") val pingMs: Double = 0.0,
    val download: SpeedTestPhase? = null,
    val upload: SpeedTestPhase? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen() {
    val scope = rememberCoroutineScope()
    val vpnStatus by VkTurnVpnService.statusFlow.collectAsState()

    var isRunning by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf<List<SpeedTestServer>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<SpeedTestServer?>(null) }
    var selectedDirection by remember { mutableStateOf("both") } // "both", "download", "upload"
    var threadsCount by remember { mutableStateOf(4) }
    var progress by remember { mutableStateOf(SpeedTestProgress()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFetchingServers by remember { mutableStateOf(false) }

    // Fetch server list on initial load
    LaunchedEffect(Unit) {
        isFetchingServers = true
        withContext(Dispatchers.IO) {
            try {
                val json = WgTurnCore.speedtestServers()
                if (json.isNotBlank() && json != "[]") {
                    val list = Gson().fromJson(json, Array<SpeedTestServer>::class.java).toList()
                    servers = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load servers", e)
            } finally {
                isFetchingServers = false
            }
        }
    }

    fun startTest() {
        if (isRunning) return
        isRunning = true
        errorMessage = null
        progress = SpeedTestProgress(state = "running", stage = "Инициализация...")

        scope.launch(Dispatchers.IO) {
            try {
                val cfg = mapOf(
                    "server_id" to (selectedServer?.id ?: ""),
                    "threads" to threadsCount,
                    "direction" to selectedDirection,
                    "duration_sec" to 10,
                    "research" to false,
                    "debug" to false
                )
                val cfgJson = Gson().toJson(cfg)
                val err = WgTurnCore.speedtestStart(cfgJson)
                if (!err.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = err
                        isRunning = false
                        progress = SpeedTestProgress(state = "error", error = err)
                    }
                    return@launch
                }

                // Poll loop
                while (isRunning) {
                    delay(150)
                    val snapJson = WgTurnCore.speedtestPoll()
                    if (snapJson.isNotBlank() && snapJson != "{}") {
                        val snap = Gson().fromJson(snapJson, SpeedTestProgress::class.java)
                        withContext(Dispatchers.Main) {
                            progress = snap
                            if (snap.state == "done" || snap.state == "error") {
                                isRunning = false
                                if (snap.state == "error") {
                                    errorMessage = snap.error ?: "Ошибка замера скорости"
                                }
                            }
                        }
                        if (snap.state == "done" || snap.state == "error") {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speed test exception", e)
                withContext(Dispatchers.Main) {
                    errorMessage = e.localizedMessage
                    isRunning = false
                    progress = SpeedTestProgress(state = "error", error = e.localizedMessage)
                }
            }
        }
    }

    fun cancelTest() {
        scope.launch(Dispatchers.IO) {
            WgTurnCore.speedtestCancel()
            withContext(Dispatchers.Main) {
                isRunning = false
                progress = progress.copy(state = "idle")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Замер скорости (Ookla)", color = TextPrimary, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // VPN Status Banner
            if (vpnStatus != VpnStatus.CONNECTED) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "VPN отключен. Замер будет выполнен через прямое соединение вашего провайдера.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Gauges & Result Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Download
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("СКАЧИВАНИЕ", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val dlMbps = progress.download?.rawMbps ?: 0.0
                        Text(
                            text = if (dlMbps > 0) "%.1f".format(dlMbps) else "--",
                            color = AccentGreen,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Мбит/с", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                // Upload
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ОТДАЧА", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val ulMbps = progress.upload?.rawMbps ?: 0.0
                        Text(
                            text = if (ulMbps > 0) "%.1f".format(ulMbps) else "--",
                            color = PrimaryBlue,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Мбит/с", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }

            // Ping and Network Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Задержка (Ping)", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = if (progress.pingMs > 0) "%.1f ms".format(progress.pingMs) else "-- ms",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    if (progress.ooklaSeesIp.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Внешний IP (Ookla)", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                text = progress.ooklaSeesIp,
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            if (progress.ooklaSeesIsp.isNotBlank()) {
                                Text(progress.ooklaSeesIsp, color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Stage Status indicator
            if (isRunning || progress.stage.isNotBlank() || progress.state == "done") {
                val stageText = when {
                    progress.state == "done" -> "Тестирование успешно завершено"
                    progress.stage == "ping" -> "Измерение задержки..."
                    progress.stage == "download" -> "Тестирование загрузки (Download)..."
                    progress.stage == "upload" -> "Тестирование отдачи (Upload)..."
                    progress.stage.isBlank() -> if (isRunning) "Подготовка..." else "Готово"
                    else -> progress.stage
                }
                Text(
                    text = stageText,
                    color = if (isRunning) PrimaryBlue else AccentGreen,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            if (errorMessage != null) {
                Text(
                    text = "Ошибка: $errorMessage",
                    color = AccentRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Параметры теста", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Direction Chips
                    Text("Направление:", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("both" to "Оба", "download" to "Download", "upload" to "Upload").forEach { (dir, title) ->
                            FilterChip(
                                selected = selectedDirection == dir,
                                onClick = { if (!isRunning) selectedDirection = dir },
                                label = { Text(title, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryBlue,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    // Threads Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Потоков (Threads): $threadsCount", color = TextSecondary, fontSize = 13.sp)
                        Slider(
                            value = threadsCount.toFloat(),
                            onValueChange = { if (!isRunning) threadsCount = it.toInt() },
                            valueRange = 1f..16f,
                            steps = 14,
                            modifier = Modifier.width(180.dp)
                        )
                    }

                    // Server selection info
                    if (servers.isNotEmpty()) {
                        Text("Сервер Ookla: ${selectedServer?.let { "${it.sponsor} (${it.name})" } ?: "Автоматический выбор (ближайший)"}", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }

            // Start / Cancel Button
            if (isRunning) {
                Button(
                    onClick = { cancelTest() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Остановить тест", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { startTest() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Запустить тест скорости", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
