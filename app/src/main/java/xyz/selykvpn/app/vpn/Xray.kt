package xyz.selykvpn.app.vpn

import android.content.Context
import android.util.Log
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.libxray.ConnectionListener
import xyz.selykvpn.libxray.Libxray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One server of auto-select as its last check saw it; [delay] is in milliseconds. */
@Serializable
data class ServerCheck(val tag: String, val alive: Boolean = false, val delay: Long = 0, val all: Int = 0, val fail: Int = 0)

/** Kotlin facade over the gomobile-generated Xray-core binding (core/libxray). */
object Xray {
    private const val TAG = "Xray"
    @Volatile
    private var initialized = false
    private val initLock = Any()

    /**
     * Extracts bundled geoip.dat and geosite.dat from assets into the app's files/xray directory
     * so Xray routing rules (geoip:private, geosite:category-ads-all, etc.) resolve properly.
     */
    fun ensureGeoFiles(context: Context, xrayDir: File) {
        synchronized(initLock) {
            val geoFiles = listOf("geoip.dat", "geosite.dat")
            for (fileName in geoFiles) {
                val target = File(xrayDir, fileName)
                if (!target.exists() || target.length() == 0L) {
                    try {
                        val assetPath = "xray/$fileName"
                        context.assets.open(assetPath).use { input ->
                            val tempFile = File(xrayDir, "$fileName.tmp")
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                            if (tempFile.exists() && tempFile.length() > 0) {
                                if (target.exists()) target.delete()
                                tempFile.renameTo(target)
                                Log.i(TAG, "Extracted geodata asset $fileName (${target.length()} bytes)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not extract asset $fileName: ${e.message}")
                    }
                }
            }
        }
    }

    private fun init(context: Context) {
        synchronized(initLock) {
            if (!initialized) {
                val assets = File(context.filesDir, "xray").apply { mkdirs() }
                ensureGeoFiles(context, assets)
                Libxray.initEnv(assets.absolutePath)
                initialized = true
            }
        }
    }

    /** Downloads updated geoip/geosite files if newer versions are available on CDN. */
    suspend fun updateGeoData(context: Context, client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        val xrayDir = File(context.filesDir, "xray").apply { mkdirs() }
        val targets = listOf(
            "geoip.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
            "geosite.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
        )
        var anyUpdated = false
        for ((fileName, url) in targets) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body ?: return@use
                        val temp = File(xrayDir, "$fileName.download")
                        temp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        if (temp.length() > 100_000L) {
                            val target = File(xrayDir, fileName)
                            if (target.exists()) target.delete()
                            temp.renameTo(target)
                            anyUpdated = true
                            Log.i(TAG, "Successfully updated $fileName to ${target.length()} bytes")
                        } else {
                            temp.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update $fileName: ${e.message}")
            }
        }
        anyUpdated
    }

    val version: String by lazy { runCatching { Libxray.xrayVersion() }.getOrDefault("") }

    /** Starts the core on the given TUN descriptor. Throws with Xray's error message on failure. */
    fun start(context: Context, config: String, tunFd: Int) {
        init(context)
        Libxray.start(config, tunFd)
    }

    fun stop() {
        runCatching { Libxray.stop() }
    }

    /** Connection log: the core reports every new connection while a listener is set. */
    fun setConnectionListener(listener: ConnectionListener?) {
        runCatching { Libxray.setConnectionListener(listener) }
    }

    /** What the running auto-select knows about its servers; null when the config has no auto-select. */
    fun observatory(): List<ServerCheck>? = runCatching {
        Libxray.observatoryStatus().takeIf { it.isNotEmpty() }?.let { AppJson.decodeFromString<List<ServerCheck>>(it) }
    }.getOrNull()

    /** Makes auto-select check its servers now; blocks for up to the check timeout (a few seconds). */
    fun recheckServers() {
        runCatching { Libxray.recheckServers() }
    }

    fun counter(name: String, reset: Boolean): Long = runCatching { Libxray.queryStats(name, reset) }.getOrDefault(0L)

    /** Real delay through the server in milliseconds, or null when it is unreachable. */
    suspend fun measureDelay(context: Context, config: String, url: String, timeoutMs: Int = 5_000): Long? =
        withContext(Dispatchers.IO) {
            init(context)
            runCatching { Libxray.measureDelay(config, url, timeoutMs) }.getOrNull()?.takeIf { it >= 0 }
        }
}
