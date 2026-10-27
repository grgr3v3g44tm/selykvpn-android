package com.vkturnproxy.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class GithubAsset(
    @SerializedName("name") val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = "",
    @SerializedName("size") val size: Long = 0L
)

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("body") val body: String = "",
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("published_at") val publishedAt: String = "",
    @SerializedName("assets") val assets: List<GithubAsset> = emptyList()
)

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long,
    val isUpdateAvailable: Boolean
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class Downloaded(val info: UpdateInfo, val apkFile: File) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    fun checkForUpdates(currentVersion: String, silent: Boolean = false) {
        scope.launch {
            if (!silent) {
                _updateState.value = UpdateState.Checking
            }

            try {
                val updateInfo = fetchLatestRelease(currentVersion)
                if (updateInfo != null && updateInfo.isUpdateAvailable) {
                    _updateState.value = UpdateState.Available(updateInfo)
                } else {
                    if (!silent) {
                        _updateState.value = UpdateState.UpToDate
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}", e)
                if (!silent) {
                    _updateState.value = UpdateState.Error(e.message ?: "Не удалось проверить обновления")
                }
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        scope.launch {
            _updateState.value = UpdateState.Downloading(info, 0f, 0L, info.fileSize)
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, info.fileName)
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                downloadFile(info.downloadUrl, apkFile) { downloaded, total ->
                    val progress = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _updateState.value = UpdateState.Downloading(info, progress, downloaded, total)
                }

                _updateState.value = UpdateState.Downloaded(info, apkFile)
                installApk(apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                _updateState.value = UpdateState.Error("Ошибка скачивания: ${e.message}")
            }
        }
    }

    fun installApk(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer: ${e.message}", e)
            _updateState.value = UpdateState.Error("Не удалось запустить установку: ${e.message}")
        }
    }

    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }

    private suspend fun fetchLatestRelease(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // per_page=100 ОБЯЗАТЕЛЕН: без параметра GitHub отдаёт только 30 релизов,
        // а новый релиз может оказаться дальше по списку — приложение его не видело
        // и показывало «обновление не найдено».
        val endpoints = listOf(
            "https://api.github.com/repos/maximyt288/my-vpn-releases/releases?per_page=100",
            "https://api.github.com/repos/maximyt288/my-vpn/releases?per_page=100"
        )

        for (endpoint in endpoints) {
            try {
                val json = fetchUrl(endpoint)
                if (json.isNullOrBlank()) continue

                val releases: Array<GithubRelease> = gson.fromJson(json, Array<GithubRelease>::class.java)
                if (releases.isEmpty()) continue

                // Кандидат — САМЫЙ СВЕЖИЙ релиз с VK TURN-ассетами.
                // Порядок в ответе GitHub нельзя принимать за «новый сверху»:
                // у релизов, созданных скриптом, created_at совпадает до секунды,
                // и порядок получается произвольным. Поэтому сортируем сами
                // по published_at.
                val candidate = releases
                    .filter { rel ->
                        (rel.tagName.startsWith("vkturn", ignoreCase = true) || !rel.tagName.startsWith("v", ignoreCase = true)) &&
                        rel.assets.any { isVkTurnApk(it.name) }
                    }
                    .maxByOrNull { rel -> rel.publishedAt }

                if (candidate != null) {
                    val remoteVersion = extractVersion(candidate.tagName)
                    val isNewer = isVersionNewer(currentVersion, remoteVersion)
                    val asset = pickAssetForDevice(candidate.assets)

                    if (asset != null) {
                        return@withContext UpdateInfo(
                            tagName = candidate.tagName,
                            versionName = remoteVersion,
                            changelog = candidate.body.ifBlank { "Обновление VK TURN Proxy v$remoteVersion" },
                            downloadUrl = asset.downloadUrl,
                            fileName = asset.name.ifEmpty { "vkturn-v$remoteVersion.apk" },
                            fileSize = asset.size,
                            isUpdateAvailable = isNewer
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Endpoint $endpoint failed: ${e.message}")
            }
        }
        null
    }

    private fun isVkTurnApk(name: String): Boolean {
        if (!name.endsWith(".apk", ignoreCase = true)) return false
        val lower = name.lowercase()
        if (lower.startsWith("koala") || lower.contains("koala.clash")) return false
        return lower.startsWith("vkturn") || lower.contains("vkturnproxy")
    }

    private fun pickAssetForDevice(assets: List<GithubAsset>): GithubAsset? {
        val apkAssets = assets.filter { isVkTurnApk(it.name) }
        if (apkAssets.isEmpty()) return null

        val deviceAbis = Build.SUPPORTED_ABIS ?: emptyArray()

        // 1. Check matching device ABIs in order of preference
        for (abi in deviceAbis) {
            val matching = apkAssets.firstOrNull {
                it.name.contains(abi, ignoreCase = true) ||
                    (abi == "arm64-v8a" && (it.name.contains("arm64", ignoreCase = true) || it.name.contains("v8a", ignoreCase = true))) ||
                    (abi == "armeabi-v7a" && (it.name.contains("armv7", ignoreCase = true) || it.name.contains("v7a", ignoreCase = true))) ||
                    (abi == "x86_64" && it.name.contains("x86_64", ignoreCase = true))
            }
            if (matching != null) return matching
        }

        // 2. Check universal APK
        val universal = apkAssets.firstOrNull {
            it.name.contains("universal", ignoreCase = true) ||
                it.name.contains("all", ignoreCase = true) ||
                it.name.equals("vkturnproxy-android.apk", ignoreCase = true)
        }
        if (universal != null) return universal

        // 3. Fallback to first available VK TURN APK
        return apkAssets.first()
    }

    private fun extractVersion(tag: String): String {
        return tag.replace(Regex("^(vkturn-|v)", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun isVersionNewer(current: String, remote: String): Boolean {
        val cleanCurrent = extractVersion(current)
        val cleanRemote = extractVersion(remote)

        val currentParts = cleanCurrent.split(".", "-").mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split(".", "-").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val cur = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > cur) return true
            if (rem < cur) return false
        }
        return false
    }

    private fun fetchUrl(urlString: String): String? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "VKTurnProxy-Android")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(
        fileUrl: String,
        destinationFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var currentUrl = fileUrl
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "VKTurnProxy-Android")
            }

            val status = conn.responseCode
            if (status in 300..399) {
                val redirectUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (redirectUrl != null) {
                    currentUrl = redirectUrl
                    redirects++
                    continue
                }
            }

            if (status !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP error $status while downloading")
            }

            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            return
        }
        throw IllegalStateException("Too many redirects")
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}
