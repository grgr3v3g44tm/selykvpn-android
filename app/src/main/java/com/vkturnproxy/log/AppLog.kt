package com.vkturnproxy.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun toFormattedLine(): String {
        val levelChar = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        return "[$formattedTime] [$levelChar/$tag] $message"
    }
}

/**
 * Thread-safe centralized in-memory diagnostic logger for VK TURN Proxy.
 * Maintains a circular buffer of the most recent events, sanitizes sensitive tokens,
 * and provides reactive StateFlow updates for the Compose UI.
 */
object AppLog {

    private const val PREFS_NAME = "vkturn_settings"
    private const val PREF_KEY_LOGS_ENABLED = "diagnostics_logging_enabled"
    private const val MAX_LOG_ENTRIES = 1000

    private val buffer = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES + 10)
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _isEnabledFlow = MutableStateFlow(false)
    val isEnabledFlow: StateFlow<Boolean> = _isEnabledFlow.asStateFlow()
    val isEnabled: Boolean get() = _isEnabledFlow.value

    private val lock = Any()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_KEY_LOGS_ENABLED, false)
        _isEnabledFlow.value = enabled
        if (enabled) {
            val info = "VK TURN v${getAppVersion(context)} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"})"
            append(LogLevel.INFO, "System", info)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_LOGS_ENABLED, enabled).apply()
        _isEnabledFlow.value = enabled
        if (enabled) {
            val info = "VK TURN v${getAppVersion(context)} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"})"
            append(LogLevel.INFO, "AppLog", "Запись логов включена пользователем")
            append(LogLevel.INFO, "System", info)
        } else {
            append(LogLevel.INFO, "AppLog", "Запись логов выключена пользователем")
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            append(LogLevel.WARN, tag, "$message: ${throwable.message ?: throwable.javaClass.simpleName}")
        } else {
            Log.w(tag, message)
            append(LogLevel.WARN, tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            append(LogLevel.ERROR, tag, "$message: ${throwable.message ?: throwable.javaClass.simpleName}")
        } else {
            Log.e(tag, message)
            append(LogLevel.ERROR, tag, message)
        }
    }

    private fun append(level: LogLevel, tag: String, rawMessage: String) {
        if (!_isEnabledFlow.value) {
            return
        }
        val sanitized = sanitize(rawMessage)
        val entry = LogEntry(level = level, tag = tag, message = sanitized)

        synchronized(lock) {
            if (buffer.size >= MAX_LOG_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _logsFlow.value = buffer.toList()
        }
    }

    /**
     * Sanitizes tokens, remixsid, passwords, and private identifiers
     * so logs can be safely shared by users.
     */
    fun sanitize(input: String): String {
        var text = input

        // Mask access_token=vk1.a... or access_token=...
        text = text.replace(Regex("""access_token=([a-zA-Z0-9_\-\.]{6})[a-zA-Z0-9_\-\.]+""")) { match ->
            "access_token=" + match.groupValues[1] + "…"
        }

        // Mask remixsid=...
        text = text.replace(Regex("""remixsid=([a-zA-Z0-9]{4})[a-zA-Z0-9]+""")) { match ->
            "remixsid=" + match.groupValues[1] + "…"
        }

        // Mask p=...
        text = text.replace(Regex("""p=([a-zA-Z0-9]{4})[a-zA-Z0-9]+""")) { match ->
            "p=" + match.groupValues[1] + "…"
        }

        // Mask PrivateKey=...
        text = text.replace(Regex("""PrivateKey\s*=\s*([a-zA-Z0-9+/=]{4})[a-zA-Z0-9+/=]+""")) { match ->
            "PrivateKey=" + match.groupValues[1] + "…"
        }

        return text
    }

    fun getFormattedText(): String {
        synchronized(lock) {
            val sb = java.lang.StringBuilder()
            sb.append("=== VK TURN DIAGNOSTIC LOG ===\n")
            sb.append("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            sb.append("Logging enabled: ${_isEnabledFlow.value}\n")
            sb.append("Entries count: ${buffer.size}\n\n")
            if (buffer.isEmpty()) {
                sb.append("(Запись логов отключена в настройках приложения или буфер пуст)\n")
            } else {
                for (entry in buffer) {
                    sb.append(entry.toFormattedLine()).append("\n")
                }
            }
            return sb.toString()
        }
    }

    fun copyToClipboard(context: Context) {
        if (!_isEnabledFlow.value && buffer.isEmpty()) {
            Toast.makeText(context, "Запись логов выключена. Включите запись в настройках или журнале.", Toast.LENGTH_LONG).show()
            return
        }
        val text = getFormattedText()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm != null) {
            val clip = ClipData.newPlainText("VK Turn Logs", text)
            cm.setPrimaryClip(clip)
            Toast.makeText(context, "Логи скопированы в буфер обмена (${buffer.size} строк)", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context) {
        if (!_isEnabledFlow.value && buffer.isEmpty()) {
            Toast.makeText(context, "Запись логов выключена. Включите запись в настройках или журнале.", Toast.LENGTH_LONG).show()
            return
        }
        val text = getFormattedText()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Отправить журнал диагностики")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logsFlow.value = emptyList()
        }
        i("AppLog", "Журнал очищен пользователем")
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
