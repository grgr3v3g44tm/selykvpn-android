package xyz.selykvpn.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.UpdateInfo
import xyz.selykvpn.app.data.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Self-update helper for direct APK distributions with PackageInstaller.Session. */
object Updater {
    private const val TAG = "Updater"
    const val SUPPORTED = true
    val installErrors: kotlinx.coroutines.flow.Flow<String> get() = UpdateInstallReceiver.installErrors

    private const val MIN_REQUIRED_FREE_BUFFER_BYTES = 50L * 1024L * 1024L
    private const val UNKNOWN_APK_ESTIMATE_BYTES = 80L * 1024L * 1024L
    private const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    private val TRUSTED_UPDATE_HOSTS = setOf(
        "selykvpn.xyz",
        "cdn-1.selykvpn.xyz",
        "tgapp.selykvpn.xyz",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    internal fun sanitizeVersionName(versionName: String): String {
        val cleaned = versionName.trim()
            .replace("..", "_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return cleaned.take(64).ifEmpty { "update" }
    }

    internal fun isTrustedUpdateUrl(rawUrl: String): Boolean {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase().trimEnd('.')
        return host in TRUSTED_UPDATE_HOSTS ||
            host.endsWith(".selykvpn.xyz") ||
            host.endsWith(".xamudhabibi.qzz.io")
    }

    internal fun isValidSha256(sha: String?): Boolean =
        sha != null && SHA256_HEX.matches(sha.trim().lowercase())

    private fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

    private fun resolveTarget(info: UpdateInfo, supportedAbis: Array<String>): Pair<String, String?> {
        val abi = supportedAbis.firstOrNull { it in info.abiUrls }
        val url = if (abi != null) info.abiUrls.getValue(abi) else info.url
        val expectedSha = if (abi != null) info.abiSha256[abi] else info.sha256
        return url to expectedSha
    }

    fun findCachedApk(dir: File, info: UpdateInfo, supportedAbis: Array<String> = Build.SUPPORTED_ABIS): File? {
        val (_, expectedSha) = resolveTarget(info, supportedAbis)
        val normalizedExpected = expectedSha?.trim()?.lowercase()?.takeIf { isValidSha256(it) } ?: return null
        val safeVer = sanitizeVersionName(info.versionName)
        val file = File(dir, "SelykVPN-$safeVer.apk")
        val insideDir = runCatching {
            file.canonicalFile.parentFile == dir.canonicalFile
        }.getOrDefault(false)
        if (!insideDir || !file.isFile || file.length() == 0L) return null
        val actual = runCatching { sha256Hex(file) }.getOrNull()
        if (actual == normalizedExpected) return file
        runCatching { file.delete() }
        return null
    }

    fun cachedApk(context: Context, info: UpdateInfo): File? =
        findCachedApk(updatesDir(context), info)

    private fun cleanOldCaches(dir: File, keepFileName: String? = null) {
        dir.listFiles()?.forEach { f ->
            if (f.name != keepFileName && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                runCatching { f.delete() }
            }
        }
    }

    suspend fun download(
        context: Context,
        http: Http,
        info: UpdateInfo,
        onProgress: (UpdateProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context).apply { mkdirs() }
        val safeVer = sanitizeVersionName(info.versionName)
        val targetFileName = "SelykVPN-$safeVer.apk"

        findCachedApk(dir, info)?.let { cached ->
            cleanOldCaches(dir, keepFileName = cached.name)
            onProgress(UpdateProgress(fraction = 1f, downloadedBytes = cached.length(), totalBytes = cached.length()))
            return@withContext cached
        }

        val (targetUrl, expectedSha) = resolveTarget(info, Build.SUPPORTED_ABIS)
        if (!isTrustedUpdateUrl(targetUrl)) {
            throw SecurityException("Untrusted update URL: $targetUrl")
        }
        val normalizedExpected = expectedSha?.trim()?.lowercase()
        if (!isValidSha256(normalizedExpected)) {
            throw SecurityException("Missing or invalid update SHA-256")
        }

        cleanOldCaches(dir)

        val target = File(dir, targetFileName)
        val part = File(dir, "$targetFileName.part")
        if (part.canonicalFile.parentFile != dir.canonicalFile || target.canonicalFile.parentFile != dir.canonicalFile) {
            throw SecurityException("Invalid update cache path")
        }

        http.withClient { client ->
            val safeClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            var currentUrl = targetUrl
            var hops = 0
            while (true) {
                if (!isTrustedUpdateUrl(currentUrl)) {
                    throw SecurityException("Untrusted redirect URL: $currentUrl")
                }
                val request = Request.Builder().url(currentUrl).build()
                safeClient.newCall(request).await().use { response ->
                    if (response.isRedirect && hops < 5) {
                        val loc = response.header("Location") ?: throw IOException("Empty redirect")
                        val resolved = request.url.resolve(loc)?.toString()
                            ?: throw SecurityException("Invalid redirect target")
                        if (!isTrustedUpdateUrl(resolved)) {
                            throw SecurityException("Untrusted redirect host: $resolved")
                        }
                        currentUrl = resolved
                        hops++
                        return@use
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.body.contentLength()
                    if (total > MAX_APK_BYTES) {
                        throw IOException("APK exceeds maximum size limit")
                    }
                    val neededSpace = (if (total > 0) total else UNKNOWN_APK_ESTIMATE_BYTES) + MIN_REQUIRED_FREE_BUFFER_BYTES
                    if (dir.usableSpace < neededSpace) {
                        throw IOException("Insufficient disk space for APK update")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    var lastEmit = 0L
                    onProgress(UpdateProgress(fraction = if (total > 0) 0f else null, downloadedBytes = 0L, totalBytes = total))

                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buf)
                                if (read < 0) break
                                done += read
                                if (done > MAX_APK_BYTES) {
                                    throw IOException("APK exceeded size limit during download")
                                }
                                digest.update(buf, 0, read)
                                output.write(buf, 0, read)
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 120L || done == total) {
                                    lastEmit = now
                                    val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                                    onProgress(UpdateProgress(fraction = fraction, downloadedBytes = done, totalBytes = total))
                                }
                            }
                        }
                    }

                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualSha != normalizedExpected) {
                        part.delete()
                        throw SecurityException("APK SHA-256 mismatch")
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    onProgress(UpdateProgress(fraction = 1f, downloadedBytes = target.length(), totalBytes = target.length()))
                    return@withClient target
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Too many redirects")
        }
    }

    fun install(context: Context, file: File) {
        val appCtx = context.applicationContext
        if (!file.isFile || file.length() <= 0L) {
            Log.e(TAG, "APK file missing for install: ${file.absolutePath}")
            return
        }
        val pkgInstaller = appCtx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            setAppPackageName(appCtx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        var session: PackageInstaller.Session? = null
        try {
            val sessionId = pkgInstaller.createSession(params)
            session = pkgInstaller.openSession(sessionId)
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val callbackIntent = Intent(appCtx, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                putExtra(UpdateInstallReceiver.EXTRA_APK_PATH, file.absolutePath)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(appCtx, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed", e)
            runCatching { session?.abandon() }
            runCatching { session?.close() }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
