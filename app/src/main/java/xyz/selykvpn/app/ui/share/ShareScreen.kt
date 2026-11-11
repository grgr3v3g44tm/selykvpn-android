package xyz.selykvpn.app.ui.share

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.ShareSettings
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.centeredMaxWidth
import xyz.selykvpn.app.ui.common.copyToClipboard
import xyz.selykvpn.app.vpn.VpnController
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.shareEnabled.collectAsStateWithLifecycle()
    val settings by viewModel.shareSettings.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()

    // Addresses change when Wi-Fi or the hotspot is switched, so re-read them while the screen is open.
    var addresses by remember { mutableStateOf(emptyList<LocalAddress>()) }
    LaunchedEffect(Unit) {
        while (true) {
            addresses = withContext(Dispatchers.IO) { localAddresses() }
            delay(3_000)
        }
    }

    val httpPort = share?.httpPort ?: 8080
    val socksPort = share?.socksPort ?: 1080
    val shownAddress = addresses.firstOrNull()?.ip ?: stringResource(R.string.share_phone_ip)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                onClick = { viewModel.setShareEnabled(!enabled) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_switch), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.share_switch_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = enabled, onCheckedChange = viewModel::setShareEnabled)
                }
            }

            AnimatedVisibility(visible = enabled) {
                val connected = vpnState is VpnController.State.Connected && share != null
                when {
                    !connected -> InfoCard(stringResource(R.string.share_waiting))
                    addresses.isEmpty() -> InfoCard(stringResource(R.string.share_no_network))
                    else -> DetailsCard(addresses, httpPort, socksPort)
                }
            }

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PortsCard(settings, onSave = viewModel::setSharePorts)
                    AuthCard(settings, onToggle = viewModel::setShareAuth, onReset = viewModel::resetShareCredentials)
                }
            }

            StepTitle(R.string.share_step1_title)
            StepText(stringResource(R.string.share_step1_text))
            OutlinedButton(onClick = { context.openHotspotSettings() }) {
                Text(stringResource(R.string.share_hotspot_settings))
            }

            StepTitle(R.string.share_step2_title)
            StepText(stringResource(R.string.share_step2_text))
            if (settings.auth && settings.user.isNotEmpty()) {
                // The password stays hidden here too: it is shown and copied in the block above.
                StepText(stringResource(R.string.share_guide_auth, settings.user, "•".repeat(settings.password.length.coerceAtMost(14))))
            }
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                val guides = listOf(
                    Guide(R.string.guide_windows, R.string.guide_windows_text, rememberVectorPainter(Icons.Rounded.DesktopWindows)),
                    Guide(R.string.guide_macos, R.string.guide_macos_text, rememberVectorPainter(Icons.Rounded.LaptopMac)),
                    Guide(R.string.guide_ios, R.string.guide_ios_text, rememberVectorPainter(Icons.Rounded.PhoneIphone)),
                    Guide(R.string.guide_android, R.string.guide_android_text, rememberVectorPainter(Icons.Rounded.Android)),
                    Guide(R.string.guide_tv, R.string.guide_tv_text, rememberVectorPainter(Icons.Rounded.Tv)),
                    Guide(R.string.guide_apps, R.string.guide_apps_text, painterResource(R.drawable.ic_telegram)),
                )
                guides.forEachIndexed { index, guide ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideRow(guide, shownAddress, httpPort, socksPort)
                }
            }

            StepTitle(R.string.share_step3_title)
            StepText(stringResource(R.string.share_step3_text))

            Row(Modifier.padding(top = 4.dp, bottom = 24.dp)) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.share_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(addresses: List<LocalAddress>, httpPort: Int, socksPort: Int) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            addresses.forEach { CopyRow(stringResource(it.kind), it.ip) }
            CopyRow(stringResource(R.string.share_http_port), httpPort.toString())
            CopyRow(stringResource(R.string.share_socks_port), socksPort.toString())
        }
    }
}

/** Own ports instead of 8080 / 1080. */
@Composable
private fun PortsCard(settings: ShareSettings, onSave: (Int, Int) -> Unit) {
    var http by rememberSaveable(settings.httpPort) { mutableStateOf(settings.httpPort.toString()) }
    var socks by rememberSaveable(settings.socksPort) { mutableStateOf(settings.socksPort.toString()) }
    val httpPort = http.toIntOrNull()
    val socksPort = socks.toIntOrNull()
    val range = ShareSettings.MIN_PORT..ShareSettings.MAX_PORT
    val valid = httpPort != null && socksPort != null && httpPort in range && socksPort in range && httpPort != socksPort
    val changed = httpPort != settings.httpPort || socksPort != settings.socksPort
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.share_ports), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = http,
                    onValueChange = { http = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.share_port_http)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = socks,
                    onValueChange = { socks = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.share_port_socks)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (valid) R.string.share_ports_hint else R.string.share_ports_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (changed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSave(httpPort!!, socksPort!!) }, enabled = valid) {
                        Text(stringResource(R.string.share_save))
                    }
                }
            }
        }
    }
}

/** Login and password for the proxy, like in INCY. */
@Composable
private fun AuthCard(settings: ShareSettings, onToggle: (Boolean) -> Unit, onReset: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!settings.auth) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.share_auth), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.share_auth_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.auth, onCheckedChange = onToggle)
        }
        if (settings.auth && settings.user.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CopyRow(stringResource(R.string.share_login), settings.user)
            SecretRow(stringResource(R.string.share_password), settings.password)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReset)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.share_reset), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SecretRow(label: String, value: String) {
    val context = LocalContext.current
    var visible by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (visible) value else "•".repeat(value.length.coerceAtMost(14)),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(20.dp))
        }
        IconButton(onClick = { context.copyToClipboard(value) }) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.copyToClipboard(value) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
        }
        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(20.dp))
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun StepTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private class Guide(@param:StringRes val title: Int, @param:StringRes val text: Int, val icon: Painter)

@Composable
private fun GuideRow(guide: Guide, address: String, httpPort: Int, socksPort: Int) {
    var expanded by rememberSaveable(guide.title) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(guide.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(guide.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = stringResource(guide.text, address, httpPort.toString(), socksPort.toString()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 36.dp, top = 8.dp),
            )
        }
    }
}

/** A private IPv4 address of this phone on Wi-Fi, hotspot, USB or Bluetooth tethering. */
private data class LocalAddress(@param:StringRes val kind: Int, val ip: String)

private fun localAddresses(): List<LocalAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            val kind = interfaceKind(nif.name) ?: return@flatMap emptyList()
            nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { LocalAddress(kind, it.hostAddress.orEmpty()) }
        }
        .distinctBy { it.ip }
        // The address shown in the guides is the first one: hotspot and Wi-Fi are the usual cases.
        .sortedBy { KIND_ORDER.indexOf(it.kind) }
}.getOrDefault(emptyList())

private val KIND_ORDER = listOf(
    R.string.share_if_hotspot,
    R.string.share_if_wifi,
    R.string.share_if_usb,
    R.string.share_if_bluetooth,
    R.string.share_if_ethernet,
)

@StringRes
private fun interfaceKind(name: String): Int? = when {
    name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
        name == "wlan1" || name == "wlan2" -> R.string.share_if_hotspot
    name.startsWith("wlan") -> R.string.share_if_wifi
    name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> R.string.share_if_usb
    name.startsWith("bt-pan") || name.startsWith("bnep") -> R.string.share_if_bluetooth
    name.startsWith("eth") -> R.string.share_if_ethernet
    else -> null // mobile data, VPN and virtual interfaces are not reachable from other devices
}

private fun Context.openHotspotSettings() {
    val candidates = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (intent in candidates) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // try the next one
        }
    }
}
