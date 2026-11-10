package xyz.selykvpn.app.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.AppIcon
import xyz.selykvpn.app.ui.common.centeredMaxWidth
import xyz.selykvpn.app.ui.common.copyToClipboard
import xyz.selykvpn.app.vpn.ConnectionLog
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val route: ConnectionLog.Route?) {
    ALL(null), VPN(ConnectionLog.Route.VPN), DIRECT(ConnectionLog.Route.DIRECT), BLOCKED(ConnectionLog.Route.BLOCKED)
}

/** Settings → Журнал подключений: apps and the addresses they opened this session, through VPN or not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionLogScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val enabled by viewModel.connectionLog.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var cleared by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The log lives in memory; look at it once a second, and only while the screen is visible.
    val initial = remember { ConnectionLog.snapshot() }
    val apps by produceState(initialValue = initial, cleared) {
        value = ConnectionLog.snapshot()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var seen = -1L
            while (true) {
                val version = ConnectionLog.version
                if (version != seen) {
                    seen = version
                    value = withContext(Dispatchers.Default) { ConnectionLog.snapshot() }
                }
                delay(1_000)
            }
        }
    }
    val shown = remember(apps, filter, query) {
        val q = query.trim()
        apps.mapNotNull { app ->
            val appMatches = q.isNotEmpty() && app.label.contains(q, ignoreCase = true)
            val entries = app.entries.filter { e ->
                (filter.route == null || e.route == filter.route) && (q.isEmpty() || appMatches || e.host.contains(q, ignoreCase = true))
            }
            if (entries.isEmpty()) null else app.copy(entries = entries)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connlog_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = {
                    if (apps.isNotEmpty()) {
                        IconButton(onClick = {
                            ConnectionLog.clear()
                            cleared++
                        }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.connlog_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(720.dp),
        ) {
            item(key = "switch") {
                Card(
                    onClick = { viewModel.setConnectionLog(!enabled) },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.connlog_enable), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.connlog_enable_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = enabled, onCheckedChange = { viewModel.setConnectionLog(it) })
                    }
                }
            }
            if (!enabled && apps.isEmpty()) return@LazyColumn

            item(key = "filters") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                ) {
                    LogFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(filterTitle(f))) },
                        )
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) }
                    },
                    placeholder = { Text(stringResource(R.string.connlog_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (shown.isEmpty()) {
                item(key = "empty") {
                    val connected = vpnState is VpnController.State.Connected
                    Text(
                        stringResource(
                            when {
                                apps.isNotEmpty() -> R.string.routing_nothing_found
                                connected -> R.string.connlog_empty
                                else -> R.string.connlog_empty_disconnected
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }

            shown.forEach { app ->
                val open = app.key in expanded
                item(key = "app-${app.key}") {
                    AppHeader(app, open) { expanded = if (open) expanded - app.key else expanded + app.key }
                }
                if (open) {
                    items(app.entries, key = { "entry-${app.key}-${it.host}-${it.route}" }) { entry -> EntryRow(entry) }
                }
            }

            item(key = "note") {
                Text(
                    stringResource(R.string.connlog_excluded_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

private fun filterTitle(filter: LogFilter) = when (filter) {
    LogFilter.ALL -> R.string.connlog_filter_all
    LogFilter.VPN -> R.string.connlog_filter_vpn
    LogFilter.DIRECT -> R.string.connlog_filter_direct
    LogFilter.BLOCKED -> R.string.connlog_filter_blocked
}

@Composable
private fun AppHeader(app: ConnectionLog.App, open: Boolean, onClick: () -> Unit) {
    val counts = app.entries.groupingBy { it.route }.eachCount()
    val parts = buildList {
        add(pluralStringResource(R.plurals.connlog_addresses, app.entries.size, app.entries.size))
        counts[ConnectionLog.Route.VPN]?.let { add(stringResource(R.string.connlog_count_vpn, it)) }
        counts[ConnectionLog.Route.DIRECT]?.let { add(stringResource(R.string.connlog_count_direct, it)) }
        counts[ConnectionLog.Route.BLOCKED]?.let { add(stringResource(R.string.connlog_count_blocked, it)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            app.packageName != null && app.packageName != "android" -> AppIcon(app.packageName)
            else -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        app.key.startsWith("share:") -> Icons.Rounded.Devices
                        app.key == "unknown" -> Icons.AutoMirrored.Rounded.HelpOutline
                        else -> Icons.Rounded.Android
                    },
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryRow(entry: ConnectionLog.Entry) {
    val context = LocalContext.current
    val format = remember { SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Addresses like uc-client-ru.heytapmobile.com are not for retyping: a tap copies it.
            .clickable { context.copyToClipboard(entry.host) }
            .padding(start = 72.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    R.string.connlog_entry_details,
                    if (entry.udp) "UDP" else "TCP",
                    entry.port,
                    entry.count,
                    format.format(Date(entry.lastSeen)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        RouteBadge(entry.route)
    }
}

@Composable
private fun RouteBadge(route: ConnectionLog.Route) {
    val colors = MaterialTheme.colorScheme
    val (text, background, content) = when (route) {
        ConnectionLog.Route.VPN -> Triple(R.string.connlog_route_vpn, colors.primaryContainer, colors.onPrimaryContainer)
        ConnectionLog.Route.DIRECT -> Triple(R.string.connlog_route_direct, colors.tertiaryContainer, colors.onTertiaryContainer)
        ConnectionLog.Route.BLOCKED -> Triple(R.string.connlog_route_blocked, colors.errorContainer, colors.onErrorContainer)
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
