package xyz.selykvpn.app.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared diagnostic log file for the isolated `:vkturn` Free Access process (`files/logs/vkturn.log`).
 * Kept free of any [com.vkturnproxy.core.WgTurnCore] reference so the main process can read and
 * clear logs without loading `libwgturn.so`.
 */
object FreeAccessLog {

    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "vkturn.log"
    private const val CRED_POOL_FILE = "creds-pool.json"
    private const val MAX_BYTES = 1_024_000L
    private const val KEEP_BYTES = 256_000

    fun logDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun logFile(context: Context): File =
        File(logDir(context), FILE_NAME)

    /** Ensures the log directory exists and trims oversized logs before Go opens [logFile]. */
    fun prepareForSession(context: Context): File {
        val file = logFile(context)
        file.parentFile?.mkdirs()
        trimIfOversized(file)
        return file
    }

    /** Clears persisted TURN credential pool cache after a bootstrap deadlock/timeout. */
    fun clearCredPoolCache(context: Context) {
        runCatching {
            val cache = File(logDir(context), CRED_POOL_FILE)
            if (cache.exists()) cache.delete()
        }
    }

    fun appendLine(context: Context, tag: String, message: String) {
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("[$ts][$tag] $message\n")
        }
    }

    fun readTail(context: Context, maxLines: Int = 250): List<String> = runCatching {
        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) return@runCatching emptyList()
        readLastBytes(file, KEEP_BYTES)
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(maxLines)
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching {
            val file = logFile(context)
            if (file.exists()) file.writeText("")
        }
    }

    private fun trimIfOversized(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        runCatching {
            val tail = readLastBytes(file, KEEP_BYTES)
            val firstNl = tail.indexOf('\n')
            val clean = if (firstNl >= 0 && firstNl + 1 < tail.length) tail.substring(firstNl + 1) else tail
            file.writeText(clean)
        }
    }

    private fun readLastBytes(file: File, maxBytes: Int): String {
        val len = file.length()
        if (len <= maxBytes) return file.readText()
        file.inputStream().use { stream ->
            stream.skip(len - maxBytes)
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}
