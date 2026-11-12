package xyz.selykvpn.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.UpdateInfo
import xyz.selykvpn.app.data.await
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Self-update for the APK distributed outside Google Play. */
object Updater {
    const val SUPPORTED = true

    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Returns a previously downloaded APK whose checksum already matches [info], if any. */
    fun cachedApk(context: Context, info: UpdateInfo): File? {
        return findCachedApk(File(context.cacheDir, "updates"), info, supportedAbis())
    }

    internal fun findCachedApk(dir: File, info: UpdateInfo, abis: Array<String>): File? {
        val file = File(dir, "SelykVPN-${info.versionName}.apk")
        if (!file.isFile || file.length() <= 0L) return null
        val expectedSha = expectedSha(info, abis)?.trim()?.takeIf { it.isNotBlank() } ?: return file
        return if (runCatching { sha256(file).equals(expectedSha, ignoreCase = true) }.getOrDefault(false)) {
            file
        } else {
            runCatching { file.delete() }
            null
        }
    }

    suspend fun download(
        context: Context,
        http: Http,
        info: UpdateInfo,
        onProgress: (UpdateProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        cachedApk(context, info)?.let { cached ->
            onProgress(UpdateProgress(fraction = 1f, downloadedBytes = cached.length(), totalBytes = cached.length()))
            return@withContext cached
        }

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "SelykVPN-${info.versionName}.apk")
        val tempFile = File(dir, "SelykVPN-${info.versionName}.apk.part")
        dir.listFiles()?.forEach { existing ->
            if (existing.name != file.name) existing.delete()
        }

        val abis = supportedAbis()
        val url = targetUrl(info, abis)
        val expected = expectedSha(info, abis)?.trim()?.takeIf { it.isNotBlank() }

        try {
            http.withClient { base ->
                val client = base.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.MINUTES)
                    .build()
                client.newCall(Request.Builder().url(url).build()).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val total = response.body.contentLength()
                    response.body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmitAt = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmitAt >= 120L || (total > 0 && done >= total)) {
                                    lastEmitAt = now
                                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                                    onProgress(UpdateProgress(fraction = fraction, downloadedBytes = done, totalBytes = total))
                                }
                            }
                            output.flush()
                        }
                    }
                }
            }
            expected?.let { hash ->
                check(sha256(tempFile).equals(hash, ignoreCase = true)) { "checksum mismatch" }
            }
            if (file.exists()) file.delete()
            check(tempFile.renameTo(file)) { "failed to finalize apk" }
            file
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun supportedAbis(): Array<String> = Build.SUPPORTED_ABIS ?: emptyArray()

    private fun targetUrl(info: UpdateInfo, abis: Array<String>): String {
        val abi = abis.firstOrNull { it in info.abiUrls }
        return abi?.let { info.abiUrls[it] } ?: info.url
    }

    private fun expectedSha(info: UpdateInfo, abis: Array<String>): String? {
        val abi = abis.firstOrNull { it in info.abiUrls }
        return if (abi != null) info.abiSha256[abi] else info.sha256
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
