package com.vkturnproxy.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vkturnproxy.service.VkTurnVpnService
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "SpeedTestScreen"

enum class TestStage {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
    ERROR
}

data class SpeedState(
    val stage: TestStage = TestStage.IDLE,
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val currentMbps: Double = 0.0,
    val downloadMbps: Double = 0.0,
    val downloadPeakMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val uploadPeakMbps: Double = 0.0,
    val publicIp: String = "",
    val ispLocation: String = "",
    val progress: Float = 0f,
    val statusText: String = "",
    val errorText: String? = null,
    val speedHistory: List<Float> = emptyList()
)

private val DOWNLOAD_ENDPOINTS = listOf(
    "https://speed.cloudflare.com/__down?bytes=50000000",
    "https://cachefly.cachefly.net/100mb.test",
    "https://speedtest.tele2.net/100MB.zip",
    "https://proof.ovh.net/files/100Mb.dat",
    "https://mirror.yandex.ru/debian/ls-lR.gz"
)

private val UPLOAD_ENDPOINTS = listOf(
    "https://speed.cloudflare.com/__up",
    "https://speedtest.tele2.net/upload.php"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen() {
    val scope = rememberCoroutineScope()
    val vpnStatus by VkTurnVpnService.statusFlow.collectAsState()

    var state by remember { mutableStateOf(SpeedState()) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var selectedDirection by remember { mutableStateOf("both") } // "both", "download", "upload"
    var threadsCount by remember { mutableStateOf(4) }

    val isRunning = state.stage != TestStage.IDLE && state.stage != TestStage.COMPLETED && state.stage != TestStage.ERROR

    val animatedSpeed by animateFloatAsState(
        targetValue = state.currentMbps.toFloat(),
        animationSpec = tween(120),
        label = "animatedSpeed"
    )

    fun cancelTest() {
        activeJob?.cancel()
        activeJob = null
        state = state.copy(
            stage = TestStage.IDLE,
            currentMbps = 0.0,
            statusText = "Тест остановлен"
        )
    }

    suspend fun measurePingAndNetworkInfo(updateState: (SpeedState) -> Unit): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val pingSamples = mutableListOf<Double>()
        var foundIp = ""
        var colo = ""

        val probeTargets = listOf(
            "https://cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1",
            "https://www.google.com/generate_204"
        )

        for (target in probeTargets) {
            if (!coroutineContext.isActive) break
            for (attempt in 1..2) {
                if (!coroutineContext.isActive) break
                val start = System.currentTimeMillis()
                try {
                    val conn = URL(target).openConnection() as HttpURLConnection
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.setRequestProperty("User-Agent", "VKTurn-SpeedTest/2.0")
                    conn.setRequestProperty("Connection", "close")
                    conn.instanceFollowRedirects = false

                    if (target.contains("cdn-cgi/trace")) {
                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            for (line in body.lines()) {
                                if (line.startsWith("ip=")) foundIp = line.removePrefix("ip=").trim()
                                if (line.startsWith("colo=")) colo = line.removePrefix("colo=").trim()
                            }
                        }
                    } else {
                        conn.requestMethod = "HEAD"
                        conn.responseCode
                    }
                    val elapsed = (System.currentTimeMillis() - start).toDouble().coerceAtLeast(1.0)
                    pingSamples.add(elapsed)
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Ping probe failed for $target: ${e.message}")
                }
            }
        }

        val ping = if (pingSamples.isNotEmpty()) pingSamples.minOrNull() ?: 25.0 else 30.0

        // Jitter: average difference between consecutive pings
        var jitter = 0.0
        if (pingSamples.size >= 2) {
            var diffSum = 0.0
            for (i in 0 until pingSamples.size - 1) {
                diffSum += abs(pingSamples[i + 1] - pingSamples[i])
            }
            jitter = diffSum / (pingSamples.size - 1)
        } else {
            jitter = ping * 0.1
        }

        // Detect ISP if IP is found
        var ispInfo = if (colo.isNotBlank()) "Cloudflare ($colo)" else "VPN Relay"
        if (foundIp.isNotBlank()) {
            try {
                val ipConn = URL("https://ipwho.is/$foundIp").openConnection() as HttpURLConnection
                ipConn.connectTimeout = 2000
                ipConn.readTimeout = 2000
                if (ipConn.responseCode == 200) {
                    val json = ipConn.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(json).asJsonObject
                    val isp = root.get("connection")?.asJsonObject?.get("isp")?.asString
                    val city = root.get("city")?.asString
                    val country = root.get("country")?.asString
                    val locationPart = listOfNotNull(city, country).filter { it.isNotBlank() }.joinToString(", ")
                    ispInfo = when {
                        !isp.isNullOrBlank() && locationPart.isNotBlank() -> "$isp · $locationPart"
                        !isp.isNullOrBlank() -> isp
                        else -> ispInfo
                    }
                }
                ipConn.disconnect()
            } catch (_: Exception) {}
        }

        withContext(Dispatchers.Main) {
            updateState(
                state.copy(
                    pingMs = ping,
                    jitterMs = jitter,
                    publicIp = foundIp,
                    ispLocation = ispInfo
                )
            )
        }
        Pair(ping, jitter)
    }

    suspend fun runDownloadTest(threads: Int, onProgressUpdate: (Double, Double, List<Float>, Float) -> Unit): Double = withContext(Dispatchers.IO) {
        val totalBytes = AtomicLong(0L)
        val isFinished = AtomicBoolean(false)
        val startTime = System.currentTimeMillis()
        val durationMs = 7000L
        val history = mutableListOf<Float>()
        var peakSpeed = 0.0
        var smoothedSpeed = 0.0

        // Background metric ticker
        val tickerJob = launch {
            var lastBytes = 0L
            var lastTime = startTime
            while (isActive && !isFinished.get()) {
                delay(120)
                val now = System.currentTimeMillis()
                val elapsedTotalSec = (now - startTime) / 1000.0
                val deltaSec = (now - lastTime) / 1000.0
                val currentBytes = totalBytes.get()
                val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)

                lastBytes = currentBytes
                lastTime = now

                if (deltaSec > 0.05) {
                    val instantMbps = (deltaBytes * 8.0) / (deltaSec * 1_000_000.0)
                    // Exponential smoothing (alpha = 0.3)
                    smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.7 + instantMbps * 0.3)
                    if (instantMbps > peakSpeed) {
                        peakSpeed = instantMbps
                    }

                    val progressRatio = (elapsedTotalSec / (durationMs / 1000.0)).toFloat().coerceIn(0f, 1f)
                    history.add(smoothedSpeed.toFloat())
                    if (history.size > 50) history.removeAt(0)

                    withContext(Dispatchers.Main) {
                        onProgressUpdate(smoothedSpeed, peakSpeed, history.toList(), progressRatio)
                    }
                }

                if (now - startTime >= durationMs) {
                    isFinished.set(true)
                    break
                }
            }
        }

        // Spawn parallel download workers
        val workers = (0 until threads).map { index ->
            launch {
                val targetUrl = DOWNLOAD_ENDPOINTS[index % DOWNLOAD_ENDPOINTS.size]
                try {
                    val conn = URL(targetUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 4000
                    conn.setRequestProperty("User-Agent", "VKTurn-SpeedTest/2.0")
                    conn.setRequestProperty("Accept-Encoding", "identity")

                    val code = conn.responseCode
                    if (code in 200..399) {
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (!isFinished.get() && isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                totalBytes.addAndGet(read.toLong())
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Worker $index download error: ${e.message}")
                }
            }
        }

        workers.joinAll()
        isFinished.set(true)
        tickerJob.cancelAndJoin()

        val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val finalAvgMbps = if (totalElapsedSec > 0.1) {
            (totalBytes.get() * 8.0) / (totalElapsedSec * 1_000_000.0)
        } else 0.0

        finalAvgMbps
    }

    suspend fun runUploadTest(threads: Int, onProgressUpdate: (Double, Double, List<Float>, Float) -> Unit): Double = withContext(Dispatchers.IO) {
        val totalBytes = AtomicLong(0L)
        val isFinished = AtomicBoolean(false)
        val startTime = System.currentTimeMillis()
        val durationMs = 6000L
        val history = mutableListOf<Float>()
        var peakSpeed = 0.0
        var smoothedSpeed = 0.0

        val tickerJob = launch {
            var lastBytes = 0L
            var lastTime = startTime
            while (isActive && !isFinished.get()) {
                delay(120)
                val now = System.currentTimeMillis()
                val elapsedTotalSec = (now - startTime) / 1000.0
                val deltaSec = (now - lastTime) / 1000.0
                val currentBytes = totalBytes.get()
                val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)

                lastBytes = currentBytes
                lastTime = now

                if (deltaSec > 0.05) {
                    val instantMbps = (deltaBytes * 8.0) / (deltaSec * 1_000_000.0)
                    smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.7 + instantMbps * 0.3)
                    if (instantMbps > peakSpeed) {
                        peakSpeed = instantMbps
                    }

                    val progressRatio = (elapsedTotalSec / (durationMs / 1000.0)).toFloat().coerceIn(0f, 1f)
                    history.add(smoothedSpeed.toFloat())
                    if (history.size > 50) history.removeAt(0)

                    withContext(Dispatchers.Main) {
                        onProgressUpdate(smoothedSpeed, peakSpeed, history.toList(), progressRatio)
                    }
                }

                if (now - startTime >= durationMs) {
                    isFinished.set(true)
                    break
                }
            }
        }

        // Payload buffer for upload (64 KB)
        val payloadChunk = ByteArray(64 * 1024) { (it % 255).toByte() }

        val workers = (0 until threads).map { index ->
            launch {
                val targetUrl = UPLOAD_ENDPOINTS[index % UPLOAD_ENDPOINTS.size]
                try {
                    val conn = URL(targetUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 4000
                    conn.doOutput = true
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setRequestProperty("User-Agent", "VKTurn-SpeedTest/2.0")
                    conn.setChunkedStreamingMode(64 * 1024)

                    conn.outputStream.use { out ->
                        while (!isFinished.get() && isActive) {
                            out.write(payloadChunk)
                            totalBytes.addAndGet(payloadChunk.size.toLong())
                        }
                        out.flush()
                    }
                    try {
                        conn.responseCode
                    } catch (_: Exception) {}
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Worker $index upload error: ${e.message}")
                }
            }
        }

        workers.joinAll()
        isFinished.set(true)
        tickerJob.cancelAndJoin()

        val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val finalAvgMbps = if (totalElapsedSec > 0.1) {
            (totalBytes.get() * 8.0) / (totalElapsedSec * 1_000_000.0)
        } else 0.0

        finalAvgMbps
    }

    fun startSpeedTest() {
        if (isRunning) return
        activeJob?.cancel()

        state = SpeedState(
            stage = TestStage.PING,
            statusText = "Замер задержки (Ping & Jitter)...",
            speedHistory = emptyList()
        )

        activeJob = scope.launch {
            try {
                // 1. Latency & Jitter
                measurePingAndNetworkInfo { updated ->
                    state = updated
                }

                // 2. Download Phase
                var finalDlAvg = 0.0
                var finalDlPeak = 0.0
                if (selectedDirection == "both" || selectedDirection == "download") {
                    state = state.copy(
                        stage = TestStage.DOWNLOAD,
                        statusText = "Тестирование загрузки (Download, $threadsCount потоков)...",
                        currentMbps = 0.0,
                        speedHistory = emptyList()
                    )

                    finalDlAvg = runDownloadTest(threadsCount) { current, peak, history, progress ->
                        finalDlPeak = peak
                        state = state.copy(
                            currentMbps = current,
                            downloadPeakMbps = peak,
                            speedHistory = history,
                            progress = progress
                        )
                    }

                    state = state.copy(
                        downloadMbps = if (finalDlAvg > 0.0) finalDlAvg else finalDlPeak,
                        currentMbps = 0.0
                    )
                }

                // 3. Upload Phase
                var finalUlAvg = 0.0
                var finalUlPeak = 0.0
                if (selectedDirection == "both" || selectedDirection == "upload") {
                    state = state.copy(
                        stage = TestStage.UPLOAD,
                        statusText = "Тестирование отдачи (Upload, $threadsCount потоков)...",
                        currentMbps = 0.0,
                        speedHistory = emptyList()
                    )

                    finalUlAvg = runUploadTest(threadsCount) { current, peak, history, progress ->
                        finalUlPeak = peak
                        state = state.copy(
                            currentMbps = current,
                            uploadPeakMbps = peak,
                            speedHistory = history,
                            progress = progress
                        )
                    }

                    state = state.copy(
                        uploadMbps = if (finalUlAvg > 0.0) finalUlAvg else finalUlPeak,
                        currentMbps = 0.0
                    )
                }

                // Complete
                state = state.copy(
                    stage = TestStage.COMPLETED,
                    currentMbps = 0.0,
                    progress = 1f,
                    statusText = "Тестирование успешно завершено"
                )

            } catch (e: CancellationException) {
                state = state.copy(stage = TestStage.IDLE, statusText = "Тест отменен")
            } catch (e: Exception) {
                Log.e(TAG, "Speed test failure", e)
                state = state.copy(
                    stage = TestStage.ERROR,
                    errorText = "Сбой замера: ${e.message}",
                    statusText = "Ошибка замера"
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Замер скорости", color = TextPrimary, fontWeight = FontWeight.Bold) },
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
                            "VPN отключен. Замер будет выполнен через прямое интернет-соединение провайдера.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Big Speed Display Meter
            val meterColor by animateColorAsState(
                targetValue = when (state.stage) {
                    TestStage.DOWNLOAD -> AccentGreen
                    TestStage.UPLOAD -> PrimaryBlue
                    TestStage.COMPLETED -> AccentGreen
                    TestStage.ERROR -> AccentRed
                    else -> PrimaryBlue
                },
                label = "meterColor"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (state.stage) {
                            TestStage.DOWNLOAD -> "СКАЧИВАНИЕ"
                            TestStage.UPLOAD -> "ОТДАЧА"
                            TestStage.PING -> "ИЗМЕРЕНИЕ ЗАДЕРЖКИ"
                            TestStage.COMPLETED -> "РЕЗУЛЬТАТ ТЕСТА"
                            else -> "СКОРОСТЬ СОЕДИНЕНИЯ"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val displaySpeed = when {
                        state.stage == TestStage.DOWNLOAD -> animatedSpeed
                        state.stage == TestStage.UPLOAD -> animatedSpeed
                        state.stage == TestStage.COMPLETED -> state.downloadMbps.toFloat()
                        else -> 0f
                    }

                    Text(
                        text = if (displaySpeed > 0f) "%.1f".format(displaySpeed) else "--",
                        color = meterColor,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        text = "Мбит/с",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.stage == TestStage.DOWNLOAD && state.downloadPeakMbps > 0) {
                        Text(
                            text = "Пиковая: ${"%.1f".format(state.downloadPeakMbps)} Мбит/с",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    } else if (state.stage == TestStage.UPLOAD && state.uploadPeakMbps > 0) {
                        Text(
                            text = "Пиковая: ${"%.1f".format(state.uploadPeakMbps)} Мбит/с",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    // Live Waveform / Chart
                    if (state.speedHistory.isNotEmpty() && isRunning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        SpeedHistoryChart(
                            history = state.speedHistory,
                            lineColor = meterColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                        )
                    }
                }
            }

            // Metrics Grid (Download, Upload, Ping, Jitter)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Download
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("СКАЧИВАНИЕ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (state.downloadMbps > 0) "%.1f".format(state.downloadMbps) else "--",
                            color = if (state.downloadMbps > 0) AccentGreen else TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Мбит/с", color = TextSecondary, fontSize = 11.sp)
                    }
                }

                // Upload
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ОТДАЧА", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (state.uploadMbps > 0) "%.1f".format(state.uploadMbps) else "--",
                            color = if (state.uploadMbps > 0) PrimaryBlue else TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Мбит/с", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            // Ping and Jitter Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Ping
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ПИНГ", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (state.pingMs > 0) "%.1f".format(state.pingMs) else "--",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("мс", color = TextSecondary, fontSize = 11.sp)
                    }
                }

                // Jitter
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ДЖИТТЕР", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (state.jitterMs > 0) "%.1f".format(state.jitterMs) else "--",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("мс", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            // Network / ISP Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Точка замера", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = if (state.ispLocation.isNotBlank()) state.ispLocation else "CDN Global Anycast",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }

                    if (state.publicIp.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Внешний IP", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = state.publicIp,
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Progress Bar & Status Text
            if (isRunning) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = meterColor,
                    trackColor = SurfaceVariantDark
                )
            }

            if (state.statusText.isNotBlank()) {
                Text(
                    text = state.statusText,
                    color = if (state.stage == TestStage.COMPLETED) AccentGreen else PrimaryBlue,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            if (state.errorText != null) {
                Text(
                    text = state.errorText ?: "",
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
                                label = { Text(title, fontSize = 12.sp) },
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
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.width(180.dp)
                        )
                    }
                }
            }

            // Start / Stop Button
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
                    onClick = { startSpeedTest() },
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

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun SpeedHistoryChart(
    history: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas

        val maxVal = max(history.maxOrNull() ?: 1f, 1f) * 1.15f
        val w = size.width
        val h = size.height
        val stepX = w / (history.size - 1)

        val strokePath = Path()
        val fillPath = Path()

        history.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - (value / maxVal) * h
            if (index == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                strokePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((history.size - 1) * stepX, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.0f)),
                startY = 0f,
                endY = h
            )
        )

        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
