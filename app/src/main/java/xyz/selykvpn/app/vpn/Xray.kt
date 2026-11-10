package xyz.selykvpn.app.vpn

import android.content.Context
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.libxray.ConnectionListener
import xyz.selykvpn.libxray.Libxray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One server of auto-select as its last check saw it; [delay] is in milliseconds. */
@Serializable
data class ServerCheck(val tag: String, val alive: Boolean = false, val delay: Long = 0, val all: Int = 0, val fail: Int = 0)

/** Kotlin facade over the gomobile-generated Xray-core binding (core/libxray). */
object Xray {
    private val initialized = AtomicBoolean(false)

    private fun init(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            val assets = File(context.filesDir, "xray").apply { mkdirs() }
            Libxray.initEnv(assets.absolutePath)
        }
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
