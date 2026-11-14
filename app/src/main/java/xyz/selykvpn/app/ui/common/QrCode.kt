package xyz.selykvpn.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.selykvpn.app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Black on white with a quiet zone in both themes: phone cameras read that best. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix by produceState<BitMatrix?>(initialValue = null, text) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
                )
            }.getOrNull()
        }
    }
    Box(
        modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val m = matrix ?: return@Canvas
            // Whole pixels per module keep the edges sharp; the rest is split around the code.
            val cell = floor(size.minDimension / m.width).coerceAtLeast(1f)
            val left = (size.width - cell * m.width) / 2
            val top = (size.height - cell * m.height) / 2
            for (y in 0 until m.height) {
                for (x in 0 until m.width) {
                    if (m[x, y]) drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size(cell, cell))
                }
            }
        }
    }
}

/**
 * On a TV, links ("Продлить", support in Telegram or VK) open on the phone: the code to scan and,
 * under it, the link itself to type in.
 */
@Composable
fun QrLinkDialog(url: String, title: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.qr_link_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title != null) {
                    Text(
                        stringResource(R.string.qr_link_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                QrCode(url, Modifier.size(240.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    url.removePrefix("https://").removePrefix("http://").removePrefix("mailto:"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
