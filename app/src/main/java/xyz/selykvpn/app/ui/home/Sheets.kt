package xyz.selykvpn.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.ServerEntry
import xyz.selykvpn.app.data.ServerFilters
import xyz.selykvpn.app.data.ServerKind
import xyz.selykvpn.app.data.SupportContact
import xyz.selykvpn.app.data.TelegramProxy
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.Ping
import xyz.selykvpn.app.ui.common.FlagAvatar
import xyz.selykvpn.app.ui.common.openUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersSheet(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Full height, so the sheet doesn't jump when a tab leaves only a few servers.
        ServersList(
            viewModel = viewModel,
            onSelected = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/**
 * Server list with ping and refresh buttons and the emoji tabs. Shown in a bottom sheet on phones
 * and as a permanent pane next to the main screen on tablets and unfolded foldables.
 */
/** TV: [enter] moves the remote's focus into the list, onto the selected server. */
class ServerListFocus {
    internal val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun enter() {
        requests.tryEmit(Unit)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersList(
    viewModel: AppViewModel,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focus: ServerListFocus? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServer.collectAsStateWithLifecycle()
    val pings by viewModel.pings.collectAsStateWithLifecycle()
    val pinging by viewModel.pinging.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val filter by viewModel.serverFilter.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val tabsState = rememberLazyListState()

    val servers = subscription?.servers.orEmpty()
    val tabs = remember(config.serverFilters, servers) { ServerFilters.available(config.serverFilters, servers) }
    val activeFilter = filter?.takeIf { emoji -> tabs.any { it.emoji == emoji } }
    val visible = remember(servers, activeFilter) { ServerFilters.apply(servers, activeFilter) }
    LaunchedEffect(activeFilter) {
        listState.scrollToItem(0)
        // Keep the selected tab (it grows with its label) on screen; index 0 is "All".
        val index = activeFilter?.let { emoji -> tabs.indexOfFirst { it.emoji == emoji } + 1 } ?: 0
        tabsState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    LaunchedEffect(Unit) {
        if (pings.isEmpty()) viewModel.testPings()
    }

    val selectedFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val visibleNow by rememberUpdatedState(visible)
    val selectedNow by rememberUpdatedState(selected)
    if (focus != null) {
        LaunchedEffect(focus) {
            focus.requests.collect {
                val index = visibleNow.indexOfFirst { it.key == selectedNow?.key }
                if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    listState.scrollToItem(index)
                    withFrameNanos { } // let the row compose before focusing it
                }
                // The selected server may be hidden by a tab: then the first server on screen.
                if (index < 0 || runCatching { selectedFocus.requestFocus() }.isFailure) {
                    runCatching { listFocus.requestFocus() }
                }
            }
        }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.servers_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            headerActions()
            // Stays enabled while pinging: pressing it again starts over.
            IconButton(onClick = viewModel::testPings) {
                if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Speed, stringResource(R.string.servers_test))
            }
            IconButton(onClick = { viewModel.refreshSubscription() }, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, stringResource(R.string.servers_update))
            }
        }
        if (tabs.isNotEmpty()) {
            LazyRow(
                state = tabsState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                item(key = "all") {
                    FilterTab(selected = activeFilter == null, onClick = { viewModel.setServerFilter(null) }) {
                        Text(stringResource(R.string.servers_filter_all))
                    }
                }
                items(tabs, key = { "${it.title}_${it.emoji}" }) { tab ->
                    val selectedTab = activeFilter == tab.emoji
                    FilterTab(selected = selectedTab, onClick = { viewModel.setServerFilter(tab.emoji) }) {
                        Text(tab.emoji, fontSize = 18.sp)
                        AnimatedVisibility(
                            visible = selectedTab,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            Text(tab.title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            modifier = Modifier
                .weight(1f)
                .focusRequester(listFocus)
                .focusGroup(),
        ) {
            visible.forEach { entry ->
                if (entry.kind == ServerKind.SEPARATOR) {
                    stickyHeader(key = entry.key) { SectionHeader(entry.title) }
                } else {
                    item(key = entry.key) {
                        ServerRow(
                            entry = entry,
                            selected = entry.key == selected?.key,
                            modifier = if (entry.key == selected?.key) Modifier.focusRequester(selectedFocus) else Modifier,
                            ping = pings[entry.key],
                            onClick = {
                                viewModel.selectServer(entry.key)
                                onSelected()
                            },
                            // Hold: ping just this server (or auto-select).
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.pingServer(entry)
                            },
                        )
                    }
                }
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.servers_hold_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }

}

@Composable
private fun FilterTab(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ServerRow(
    entry: ServerEntry,
    selected: Boolean,
    ping: Ping?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagAvatar(entry.flag, entry.kind, size = 40.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        PingText(entry.kind, ping)
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(contacts: List<SupportContact>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text(
                stringResource(R.string.support_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.support_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            contacts.forEach { contact ->
                SupportRow(contact) { context.openUrl(contact.url, contact.title) }
            }
        }
    }
}

/** Telegram proxies: tapping one hands its link to Telegram, which offers to add it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramProxiesSheet(proxies: List<TelegramProxy>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.tg_proxies_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.tg_proxies_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            proxies.forEach { proxy ->
                ListItem(
                    headlineContent = { Text(proxy.title) },
                    leadingContent = {
                        if (proxy.flag.isNullOrBlank()) {
                            Icon(painterResource(R.drawable.ic_telegram), null, tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(proxy.flag, style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    trailingContent = {
                        Text(
                            stringResource(R.string.tg_proxies_add),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { context.openUrl(proxy.url.trim()) },
                )
            }
        }
    }
}

@Composable
fun SupportRow(contact: SupportContact, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(contact.title) },
        leadingContent = {
            when (contact.type) {
                "telegram" -> Icon(painterResource(R.drawable.ic_telegram), null, tint = MaterialTheme.colorScheme.primary)
                "vk" -> Icon(Icons.Rounded.Forum, null, tint = MaterialTheme.colorScheme.primary)
                "email" -> Icon(Icons.Rounded.Email, null, tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
