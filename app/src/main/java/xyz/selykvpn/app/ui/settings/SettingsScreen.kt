package xyz.selykvpn.app.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.BuildConfig
import xyz.selykvpn.app.R
import xyz.selykvpn.app.isTv
import xyz.selykvpn.app.data.UPDATE_FROM_SUBSCRIPTION
import xyz.selykvpn.app.data.UPDATE_MANUAL
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.centeredMaxWidth
import xyz.selykvpn.app.ui.common.isWideWindow
import xyz.selykvpn.app.ui.common.openUrl
import xyz.selykvpn.app.ui.home.SupportRow
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import xyz.selykvpn.app.update.Updater
import xyz.selykvpn.app.vpn.Xray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onSplitTunnel: () -> Unit,
    onShare: () -> Unit,
    onRouting: () -> Unit,
    onConnectionLog: () -> Unit,
    onChangeKey: () -> Unit,
    onConnectTv: () -> Unit,
) {
    val context = LocalContext.current
    val tv = remember { context.isTv() }
    // The TV's QR code is scanned with this phone's camera.
    val canScanTv = remember { !tv && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val vpnSettings = remember {
        Intent(Settings.ACTION_VPN_SETTINGS).takeIf { it.resolveActivity(context.packageManager) != null }
    }
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val shareEnabled by viewModel.shareEnabled.collectAsStateWithLifecycle()
    val serversOnLeft by viewModel.serversOnLeft.collectAsStateWithLifecycle()
    val wide = isWideWindow()
    // Count only apps that are installed on this phone.
    val excludedCount = remember(exclusions) {
        exclusions.effective.count { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
    }
    val config by viewModel.config.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var chooseInterval by rememberSaveable { mutableStateOf(false) }
    val updateHours by viewModel.subscriptionUpdateHours.collectAsStateWithLifecycle()
    val whitelistDetect by viewModel.whitelistDetect.collectAsStateWithLifecycle()
    val connectionLog by viewModel.connectionLog.collectAsStateWithLifecycle()
    val themePalette by viewModel.themePalette.collectAsStateWithLifecycle()
    val subscriptionHours = subscription?.updateIntervalHours ?: 6

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(720.dp),
        ) {
            item { SectionTitle(R.string.settings_subscription) }
            item {
                val justNow = stringResource(R.string.time_just_now)
                val updated = subscription?.fetchedAt?.let {
                    val now = System.currentTimeMillis()
                    if (now - it < 60_000) justNow
                    else DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
                }
                SettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.settings_update_subscription),
                    subtitle = updated?.let { stringResource(R.string.settings_updated_at, it) },
                    trailing = { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    onClick = { viewModel.refreshSubscription() },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.settings_auto_update),
                    subtitle = intervalLabel(updateHours, subscriptionHours),
                    onClick = { chooseInterval = true },
                )
            }
            item { SettingsRow(Icons.Rounded.Key, stringResource(R.string.settings_change_key), onClick = onChangeKey) }
            if (canScanTv) {
                item {
                    SettingsRow(
                        icon = Icons.Rounded.Tv,
                        title = stringResource(R.string.settings_connect_tv),
                        subtitle = stringResource(R.string.settings_connect_tv_hint),
                        onClick = onConnectTv,
                    )
                }
            }
            item {
                SettingsRow(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.settings_logout), onClick = { confirmLogout = true })
            }

            item { SectionTitle(R.string.settings_vpn) }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.settings_split),
                    subtitle = if (excludedCount == 0) stringResource(R.string.settings_split_none)
                    else stringResource(R.string.settings_split_count, excludedCount),
                    onClick = onSplitTunnel,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.settings_recommended),
                    subtitle = stringResource(R.string.settings_recommended_hint),
                    trailing = {
                        Switch(checked = exclusions.useRecommended, onCheckedChange = viewModel::setUseRecommendedExclusions)
                    },
                    onClick = { viewModel.setUseRecommendedExclusions(!exclusions.useRecommended) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    title = stringResource(R.string.settings_routing),
                    subtitle = stringResource(R.string.settings_routing_hint),
                    onClick = onRouting,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.FactCheck,
                    title = stringResource(R.string.settings_whitelist),
                    subtitle = stringResource(R.string.settings_whitelist_hint),
                    trailing = { Switch(checked = whitelistDetect, onCheckedChange = { viewModel.setWhitelistDetect(it) }) },
                    onClick = { viewModel.setWhitelistDetect(!whitelistDetect) },
                )
            }
            // Sharing the VPN from a TV makes no sense: it's for phones on mobile internet.
            if (!tv) {
                item {
                    SettingsRow(
                        icon = Icons.Rounded.WifiTethering,
                        title = stringResource(R.string.settings_share),
                        subtitle = stringResource(if (shareEnabled) R.string.share_on else R.string.share_off),
                        onClick = onShare,
                    )
                }
            }
            // Android TV usually has no VPN settings screen.
            if (vpnSettings != null) {
                item {
                    SettingsRow(
                        icon = Icons.Rounded.VpnLock,
                        title = stringResource(R.string.settings_system_vpn),
                        subtitle = stringResource(R.string.settings_system_vpn_hint),
                        onClick = { runCatching { context.startActivity(vpnSettings) } },
                    )
                }
            }

            item { SectionTitle(R.string.settings_interface) }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.theme_palette_title),
                    subtitle = if (themePalette == "graphite") stringResource(R.string.theme_graphite_cool) else stringResource(R.string.theme_selyk_premium),
                    onClick = {
                        val next = if (themePalette == "graphite") "selyk" else "graphite"
                        viewModel.setThemePalette(next)
                    },
                )
            }
            if (wide) {
                item {
                    SettingsRow(
                        icon = Icons.Rounded.SwapHoriz,
                        title = stringResource(R.string.settings_servers_left),
                        subtitle = stringResource(R.string.settings_servers_left_hint),
                        trailing = { Switch(checked = serversOnLeft, onCheckedChange = { viewModel.setServersOnLeft(it) }) },
                        onClick = { viewModel.setServersOnLeft(!serversOnLeft) },
                    )
                }
            }

            item { SectionTitle(R.string.settings_diagnostics) }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    title = stringResource(R.string.settings_connection_log),
                    subtitle = stringResource(if (connectionLog) R.string.settings_connection_log_on else R.string.settings_connection_log_off),
                    onClick = onConnectionLog,
                )
            }

            item { SectionTitle(R.string.settings_support) }
            config.support.forEach { contact ->
                item { SupportRow(contact) { context.openUrl(contact.url, contact.title) } }
            }

            item { SectionTitle(R.string.settings_about) }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    subtitle = "Xray-core ${Xray.version}",
                )
            }
            if (Updater.SUPPORTED) {
                item {
                    SettingsRow(Icons.Rounded.SystemUpdate, stringResource(R.string.settings_check_update), onClick = { viewModel.checkForUpdates() })
                }
            }
        }
    }

    if (chooseInterval) {
        AlertDialog(
            onDismissRequest = { chooseInterval = false },
            title = { Text(stringResource(R.string.settings_auto_update)) },
            text = {
                Column {
                    INTERVALS.forEach { hours ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSubscriptionUpdateHours(hours)
                                    chooseInterval = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = hours == updateHours, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(intervalLabel(hours, subscriptionHours))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseInterval = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.settings_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private val INTERVALS = listOf(UPDATE_FROM_SUBSCRIPTION, 1, 3, 6, 12, 24, UPDATE_MANUAL)

@Composable
private fun intervalLabel(hours: Int, subscriptionHours: Int): String = when (hours) {
    UPDATE_FROM_SUBSCRIPTION -> stringResource(R.string.update_interval_subscription, subscriptionHours)
    UPDATE_MANUAL -> stringResource(R.string.update_interval_manual)
    else -> pluralStringResource(R.plurals.update_every_hours, hours, hours)
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Preview(name = "Settings Compact 360dp", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Settings Wide 600dp Light", widthDp = 600, heightDp = 640, showBackground = true)
@Composable
fun SettingsPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Scaffold { padding ->
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .centeredMaxWidth(720.dp),
            ) {
                item { SectionTitle(R.string.settings_subscription) }
                item {
                    SettingsRow(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.settings_update_subscription),
                        subtitle = "Только что",
                    )
                }
                item {
                    SettingsRow(
                        icon = Icons.Rounded.Key,
                        title = stringResource(R.string.settings_change_key),
                    )
                }
                item {
                    SettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.theme_palette_title),
                        subtitle = stringResource(R.string.theme_selyk_premium),
                    )
                }
            }
        }
    }
}
