package xyz.selykvpn.app.ui.home

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.NetworkKind
import xyz.selykvpn.app.data.ProbeError
import xyz.selykvpn.app.data.ProbeResult
import xyz.selykvpn.app.data.WhitelistCheck
import xyz.selykvpn.app.data.WhitelistStatus
import xyz.selykvpn.app.ui.AppViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun statusText(status: WhitelistStatus): String = stringResource(
    when (status) {
        WhitelistStatus.ALL_OK -> R.string.whitelist_all_ok
        WhitelistStatus.WHITELIST -> R.string.whitelist_on
        WhitelistStatus.NO_INTERNET -> R.string.whitelist_none
        WhitelistStatus.UNKNOWN -> R.string.whitelist_checking
    },
)

@Composable
private fun statusColor(status: WhitelistStatus): Color {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        WhitelistStatus.ALL_OK -> colors.tertiary
        WhitelistStatus.WHITELIST -> Color(0xFFE8892B)
        WhitelistStatus.NO_INTERNET -> colors.error
        WhitelistStatus.UNKNOWN -> colors.outline
    }
}

/** "Белые списки" on the main screen; hidden when the detection is turned off in settings. */
@Composable
internal fun WhitelistCard(viewModel: AppViewModel) {
    val enabled by viewModel.whitelistDetect.collectAsStateWithLifecycle()
    val status by viewModel.whitelistStatus.collectAsStateWithLifecycle()
    val checking by viewModel.whitelistChecking.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    if (!enabled) return
    Card(
        onClick = { details = true },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.whitelist_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(statusText(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Box(Modifier.size(12.dp).background(statusColor(status), CircleShape))
                }
            }
        }
    }
    if (details) {
        val check by viewModel.whitelistLastCheck.collectAsStateWithLifecycle()
        WhitelistSheet(status, check, checking, onCheck = viewModel::checkWhitelist, onDismiss = { details = false })
    }
}

/** When the sites were checked, how each one answered, and that it was done without VPN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhitelistSheet(
    status: WhitelistStatus,
    check: WhitelistCheck?,
    checking: Boolean,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val timeFormat = remember { SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.whitelist_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(statusColor(status), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(statusText(status), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(8.dp))
            val checked = check?.let {
                val ago = if (now - it.time < 60_000) stringResource(R.string.time_just_now)
                else DateUtils.getRelativeTimeSpanString(it.time, now, DateUtils.MINUTE_IN_MILLIS).toString()
                stringResource(R.string.whitelist_last_check, timeFormat.format(Date(it.time)), ago) +
                    "\n" + stringResource(R.string.whitelist_network, stringResource(networkName(it.network)))
            } ?: stringResource(R.string.whitelist_never_checked)
            Text(checked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (check != null) {
                Spacer(Modifier.height(20.dp))
                ProbeGroup(R.string.whitelist_probes_blocked, check.blocked)
                Spacer(Modifier.height(16.dp))
                ProbeGroup(R.string.whitelist_probes_allowed, check.allowed)
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.whitelist_direct_note), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.whitelist_how),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.whitelist_check_again))
            }
        }
    }
}

@Composable
private fun ProbeGroup(@StringRes title: Int, results: List<ProbeResult>) {
    val colors = MaterialTheme.colorScheme
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = colors.primary)
    Spacer(Modifier.height(4.dp))
    results.forEach { probe ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (probe.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                null,
                tint = if (probe.ok) colors.tertiary else colors.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(probe.host, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                probeText(probe),
                style = MaterialTheme.typography.bodyMedium,
                color = if (probe.ok) colors.onSurfaceVariant else colors.error,
            )
        }
    }
}

@Composable
private fun probeText(probe: ProbeResult): String = when (probe.error) {
    null -> stringResource(R.string.probe_ok, probe.ms ?: 0L)
    ProbeError.TIMEOUT -> stringResource(R.string.probe_timeout)
    ProbeError.DNS -> stringResource(R.string.probe_dns)
    ProbeError.CONNECT -> stringResource(R.string.probe_connect)
    ProbeError.TLS -> stringResource(R.string.probe_tls)
    ProbeError.OTHER -> stringResource(R.string.probe_error)
}

@StringRes
private fun networkName(kind: NetworkKind) = when (kind) {
    NetworkKind.WIFI -> R.string.network_wifi
    NetworkKind.MOBILE -> R.string.network_mobile
    NetworkKind.ETHERNET -> R.string.network_ethernet
    NetworkKind.OTHER -> R.string.network_other
    NetworkKind.NONE -> R.string.network_none
}
