package xyz.selykvpn.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import xyz.selykvpn.app.R
import xyz.selykvpn.app.isTv
import xyz.selykvpn.app.data.ServerKind
import xyz.selykvpn.app.ui.Ping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Keeps content readable on tablets: at most [max] wide, centered. */
fun Modifier.centeredMaxWidth(max: Dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** Same breakpoint as the two-pane main screen. */
@Composable
fun isWideWindow(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** [title] names the link on a TV, where it is shown as a QR code (e.g. "Telegram"). */
fun Context.openUrl(url: String, title: String? = null) {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("https", "http", "mailto", "tg")) return
    // A TV has no browser or Telegram to open it in: the link is shown as a QR code for the phone.
    if (isTv()) {
        LinkQr.show(url, title)
        return
    }
    runCatching {
        val intent = if (scheme == "mailto") Intent(Intent.ACTION_SENDTO, uri) else Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { Toast.makeText(this, getString(R.string.open_url_failed), Toast.LENGTH_SHORT).show() }
    }
}

/** The link [openUrl] shows as a QR code on a TV (AppRoot draws the dialog). */
object LinkQr {
    data class Link(val url: String, val title: String?)

    private val _link = MutableStateFlow<Link?>(null)
    val link: StateFlow<Link?> = _link.asStateFlow()

    fun show(url: String, title: String? = null) {
        _link.value = Link(url, title)
    }

    fun dismiss() {
        _link.value = null
    }
}

private object AppIconCache {
    val lru = android.util.LruCache<String, ImageBitmap>(48)
}

/** Icon of an installed app, loaded in the background; empty space while loading or when unknown. */
@Composable
fun AppIcon(packageName: String?, size: Dp = 40.dp) {
    val context = LocalContext.current
    val cached = remember(packageName) { packageName?.let { AppIconCache.lru.get(it) } }
    val icon by produceState<ImageBitmap?>(initialValue = cached, packageName) {
        if (packageName == null) {
            value = null
            return@produceState
        }
        AppIconCache.lru.get(packageName)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { AppIconCache.lru.put(packageName, it) }
        }
    }
    Box(Modifier.size(size)) {
        icon?.let { Image(it, null, Modifier.size(size)) }
    }
}

fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Селюк VPN", text))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, getString(R.string.share_copied), Toast.LENGTH_SHORT).show()
    }
}

fun Context.readClipboard(): String? {
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { index ->
        clip.getItemAt(index).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }
}

fun Context.formatBytes(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

fun Context.formatSpeed(bytesPerSecond: Long): String = getString(R.string.speed_format, formatBytes(bytesPerSecond))

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
}

@Composable
fun FlagAvatar(flag: String?, kind: ServerKind, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            flag != null -> Text(flag, fontSize = (size.value * 0.5f).sp)
            kind == ServerKind.AUTO -> Icon(Icons.Rounded.Autorenew, null, tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun pingColor(ping: Ping?): Color = when (ping) {
    is Ping.Ok -> when {
        // Real delay: a full HTTPS request through the server, not an ICMP ping.
        ping.ms <= 550 -> MaterialTheme.colorScheme.tertiary
        ping.ms <= 1100 -> Color(0xFFE8892B)
        else -> MaterialTheme.colorScheme.error
    }
    Ping.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val LINK_REGEX = Regex("""(https?://[^\s]+)|(@[A-Za-z0-9_]{4,})|(\b[A-Za-z0-9-]+\.(?:info|ru|com|net|app|me)\b(?:/[^\s]*)?)""")

/** Makes URLs, @telegram handles and bare domains in announcement text clickable. */
@Composable
fun rememberLinkified(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var last = 0
            LINK_REGEX.findAll(text).forEach { match ->
                append(text.substring(last, match.range.first))
                val value = match.value.trimEnd('.', ',', ')', '!')
                val url = when {
                    value.startsWith("@") -> "https://t.me/${value.drop(1)}"
                    value.startsWith("http") -> value
                    else -> "https://$value"
                }
                pushLink(LinkAnnotation.Url(url, styles))
                append(value)
                pop()
                append(match.value.substring(value.length))
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
}
