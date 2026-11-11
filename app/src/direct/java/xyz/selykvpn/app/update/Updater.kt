package xyz.selykvpn.app.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.UpdateInfo
import xyz.selykvpn.app.data.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Self-update for the APK distributed outside Google Play. */
object Updater {
    const val SUPPORTED = true

    suspend fun download(context: Context, http: Http, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "SelykVPN-${info.versionName}.apk")
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in info.abiUrls }
            val url = abi?.let { info.abiUrls[it] } ?: info.url
            val expectedSha = if (abi != null) info.abiSha256[abi] else info.sha256
            http.withClient { base ->
                val client = base.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(url).build()).await().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val total = response.body.contentLength()
                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
            expectedSha?.takeIf { it.isNotBlank() }?.let { expected ->
                check(sha256(file).equals(expected.trim(), ignoreCase = true)) { "checksum mismatch" }
            }
            file
        }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
