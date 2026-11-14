package xyz.selykvpn.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import xyz.selykvpn.app.ui.common.QrCode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.isTv
import xyz.selykvpn.app.data.AccountStatus
import xyz.selykvpn.app.data.AppLink
import xyz.selykvpn.app.data.ServerEntry
import xyz.selykvpn.app.data.ServerKind
import xyz.selykvpn.app.data.SubscriptionInfo
import xyz.selykvpn.app.data.UpdateInfo
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.Ping
import xyz.selykvpn.app.update.UpdateProgress
import xyz.selykvpn.app.ui.common.FlagAvatar
import xyz.selykvpn.app.ui.common.formatBytes
import xyz.selykvpn.app.ui.common.formatDuration
import xyz.selykvpn.app.ui.common.formatSpeed
import xyz.selykvpn.app.ui.common.openUrl
import xyz.selykvpn.app.ui.common.pingColor
import xyz.selykvpn.app.ui.common.rememberLinkified
import xyz.selykvpn.app.vpn.VpnController
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Windows at least this wide (unfolded foldables, tablets) show the server list next to the main screen. */
private val TwoPaneMinWidth = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel, onConnect: () -> Unit, onOpenSettings: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val serversOnLeft by viewModel.serversOnLeft.collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showFreeAccessSetup by rememberSaveable { mutableStateOf(false) }
    var showSubscriptionSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_selyk_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (maxWidth >= TwoPaneMinWidth) {
                val context = LocalContext.current
                val tv = remember { context.isTv() }
                val listFocus = remember { ServerListFocus() }
                // TV: the remote's key towards the list goes straight to the servers, from anywhere on the left.
                val towardsList = if (serversOnLeft) Key.DirectionLeft else Key.DirectionRight
                val jumpToList = Modifier.onPreviewKeyEvent { event ->
                    if (tv && event.type == KeyEventType.KeyDown && event.key == towardsList) {
                        listFocus.enter()
                        true
                    } else {
                        false
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val home = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(jumpToList)
                    val servers = Modifier.weight(1f)
                    if (serversOnLeft) {
                        ServersPane(viewModel, onSwap = { viewModel.setServersOnLeft(false) }, focus = listFocus, modifier = servers)
                        HomeColumn(
                            viewModel = viewModel,
                            onConnect = onConnect,
                            onServerClick = null,
                            onFreeAccessClick = { showFreeAccessSetup = true },
                            onSubscriptionClick = { showSubscriptionSheet = true },
                            onSupport = { showSupport = true },
                            modifier = home,
                        )
                    } else {
                        HomeColumn(
                            viewModel = viewModel,
                            onConnect = onConnect,
                            onServerClick = null,
                            onFreeAccessClick = { showFreeAccessSetup = true },
                            onSubscriptionClick = { showSubscriptionSheet = true },
                            onSupport = { showSupport = true },
                            modifier = home,
                        )
                        ServersPane(viewModel, onSwap = { viewModel.setServersOnLeft(true) }, focus = listFocus, modifier = servers)
                    }
                }
            } else {
                HomeColumn(
                    viewModel = viewModel,
                    onConnect = onConnect,
                    onServerClick = { showServers = true },
                    onFreeAccessClick = { showFreeAccessSetup = true },
                    onSubscriptionClick = { showSubscriptionSheet = true },
                    onSupport = { showSupport = true },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showServers) ServersSheet(viewModel = viewModel, onDismiss = { showServers = false })
            }
        }
    }

    if (showSupport) SupportSheet(contacts = config.support, onDismiss = { showSupport = false })
    if (showFreeAccessSetup) FreeAccessSetupSheet(viewModel = viewModel, onDismiss = { showFreeAccessSetup = false })
    if (showSubscriptionSheet) {
        val subscription by viewModel.subscription.collectAsStateWithLifecycle()
        val account by viewModel.account.collectAsStateWithLifecycle()
        SubscriptionSheet(
            subscription = subscription,
            account = account,
            botUrl = config.telegramBotUrl,
            onDismiss = { showSubscriptionSheet = false },
        )
    }
}

/** The main screen itself: status, connect button, current server, subscription, links. */
@Composable
private fun HomeColumn(
    viewModel: AppViewModel,
    onConnect: () -> Unit,
    onServerClick: (() -> Unit)?,
    onFreeAccessClick: () -> Unit,
    onSubscriptionClick: () -> Unit,
    onSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tv = remember { context.isTv() }
    var showProxies by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.vpnState.collectAsStateWithLifecycle()
    val server by viewModel.selectedServer.collectAsStateWithLifecycle()
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val pings by viewModel.pings.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
    val activeMode by viewModel.activeMode.collectAsStateWithLifecycle()
    val freeAccessStage by viewModel.freeAccessStage.collectAsStateWithLifecycle()
    val freeAccessConfig by viewModel.freeAccessConfig.collectAsStateWithLifecycle()
    val freeAccessAllowed by viewModel.freeAccessAllowed.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = modifier) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minHeight)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top & Main content
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    ModeSegmentedSwitch(
                        activeMode = activeMode,
                        onModeChange = viewModel::setMode,
                        enabled = state == VpnController.State.Idle,
                        freeAccessAllowed = freeAccessAllowed,
                        onLockedFreeAccessClick = viewModel::notifyFreeAccessLocked,
                    )
                    Spacer(Modifier.height(12.dp))
                    StatusHeader(
                        state = state,
                        freeAccessStage = if (activeMode == "free_access") freeAccessStage else null,
                    )
                    SessionTraffic(visible = state is VpnController.State.Connected, viewModel = viewModel)
                    Spacer(Modifier.height(14.dp))
                    ConnectButton(
                        state = state,
                        onClick = { if (state == VpnController.State.Idle) onConnect() else viewModel.disconnect() },
                    )
                    TrafficRow(visible = state is VpnController.State.Connected, viewModel = viewModel)
                    Spacer(Modifier.height(16.dp))
                    if (activeMode == "regular") {
                        ServerCard(server = server, ping = server?.let { pings[it.key] }, onClick = onServerClick)
                        Spacer(Modifier.height(10.dp))
                        WhitelistCard(viewModel)
                    } else {
                        FreeAccessCard(
                            connected = state is VpnController.State.Connected,
                            config = freeAccessConfig,
                            onClick = onFreeAccessClick,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AccountCard(
                        account = account,
                        subscription = subscription,
                        onRenew = {
                            val id = subscription?.url?.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() }
                            context.openUrl(id?.let(config.site::renewalUrl) ?: config.renewUrl, context.getString(R.string.renew_qr_title))
                        },
                    )
                    update?.let {
                        Spacer(Modifier.height(10.dp))
                        UpdateCard(
                            info = it,
                            progress = updateProgress,
                            onInstall = viewModel::installUpdate,
                            onSkip = viewModel::skipUpdate,
                            onCancel = viewModel::cancelUpdate,
                        )
                    }
                    subscription?.announce?.let {
                        Spacer(Modifier.height(10.dp))
                        AnnounceCard(it)
                    }
                }

                // Bottom actions: Website, Bot, Support (and optional TG proxies)
                if (!tv) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QuickActions(
                            links = config.links,
                            onOpen = { context.openUrl(it) },
                            onSubscription = onSubscriptionClick,
                            onSupport = onSupport,
                        )
                        if (config.telegramProxies.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            TelegramProxiesButton(onClick = { showProxies = true })
                        }
                    }
                }
            }
        }
    }
    if (showProxies) TelegramProxiesSheet(proxies = config.telegramProxies, onDismiss = { showProxies = false })
}

/** Permanent server list for the two-pane layout; the swap button moves it to the other side. */
@Composable
private fun ServersPane(viewModel: AppViewModel, onSwap: () -> Unit, focus: ServerListFocus, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxHeight(),
    ) {
        ServersList(
            viewModel = viewModel,
            onSelected = {},
            focus = focus,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp),
            headerActions = {
                IconButton(onClick = onSwap) {
                    Icon(Icons.Rounded.SwapHoriz, stringResource(R.string.servers_swap_side))
                }
            },
        )
    }
}

@Composable
private fun StatusHeader(state: VpnController.State, freeAccessStage: String? = null) {
    val title = when (state) {
        is VpnController.State.Connected -> R.string.status_connected
        VpnController.State.Connecting -> R.string.status_connecting
        VpnController.State.Stopping -> R.string.status_disconnecting
        VpnController.State.Idle -> R.string.status_disconnected
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (state == VpnController.State.Connecting && !freeAccessStage.isNullOrBlank()) {
            Text(
                text = freeAccessStage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val since = (state as? VpnController.State.Connected)?.since
        if (since != null) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(since, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
            }
            Text(
                text = formatDuration(now - since),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectButton(state: VpnController.State, onClick: () -> Unit) {
    val connected = state is VpnController.State.Connected
    val busy = state == VpnController.State.Connecting || state == VpnController.State.Stopping
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (connected) colors.primary else colors.surfaceContainerHigh, label = "container")
    val content by animateColorAsState(if (connected) colors.onPrimary else colors.onSurface, label = "content")
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animateHalo = connected && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        if (animateHalo) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val haloScale by pulse.animateFloat(
                initialValue = 0.92f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                label = "halo",
            )
            Box(
                Modifier
                    .size(190.dp)
                    .scale(haloScale)
                    .background(colors.primary.copy(alpha = 0.14f), CircleShape),
            )
        } else if (connected) {
            Box(
                Modifier
                    .size(190.dp)
                    .background(colors.primary.copy(alpha = 0.14f), CircleShape),
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(176.dp), strokeWidth = 3.dp)
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = if (connected) 6.dp else 0.dp,
            modifier = Modifier.size(156.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(52.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(if (state == VpnController.State.Idle) R.string.action_connect else R.string.action_disconnect),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Traffic of the current connection, under the timer. */
@Composable
private fun SessionTraffic(visible: Boolean, viewModel: AppViewModel) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val context = LocalContext.current
        val traffic by viewModel.traffic.collectAsStateWithLifecycle()
        Text(
            text = stringResource(R.string.session_traffic, context.formatBytes(traffic.totalDown), context.formatBytes(traffic.totalUp)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Whether the operator lets through only white-listed services (Settings can turn this off). */
@Composable
private fun TrafficRow(visible: Boolean, viewModel: AppViewModel) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val context = LocalContext.current
        val traffic by viewModel.traffic.collectAsStateWithLifecycle()
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            SpeedLabel(Icons.Rounded.ArrowDownward, context.formatSpeed(traffic.down))
            SpeedLabel(Icons.Rounded.ArrowUpward, context.formatSpeed(traffic.up))
        }
    }
}

@Composable
private fun SpeedLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerCard(server: ServerEntry?, ping: Ping?, onClick: (() -> Unit)?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // In the two-pane layout the list is already on screen, so the card is not a button there.
        Row(
            modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagAvatar(server?.flag, server?.kind ?: ServerKind.SERVER)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    stringResource(R.string.server_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = server?.title ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PingText(server?.kind, ping)
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PingText(kind: ServerKind?, ping: Ping?) {
    val text = when {
        ping is Ping.Ok -> stringResource(R.string.ping_ms, ping.ms.toInt())
        ping == Ping.Failed -> stringResource(R.string.ping_failed)
        kind == ServerKind.AUTO -> stringResource(R.string.server_auto)
        else -> null
    }
    if (ping == Ping.Testing) {
        CircularProgressIndicator(Modifier.size(16.dp).padding(end = 2.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
    } else if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (ping == null) MaterialTheme.colorScheme.primary else pingColor(ping),
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/** "Продлить" appears when less than this is left of the plan. */
private const val RENEW_BEFORE_SECONDS = 2 * 24 * 3600L

@Composable
private fun AccountCard(account: AccountStatus?, subscription: SubscriptionInfo?, onRenew: () -> Unit) {
    val context = LocalContext.current
    val found = account?.found == true
    // Fall back to the expiry date from the subscription headers when the API is unreachable.
    val expiresAt = if (found) account.expiresAt else subscription?.expire
    val planName = account?.planName?.takeIf { found }
    val isFree = found && account.isFree
    if (!found && expiresAt == null) return

    val nowSeconds = System.currentTimeMillis() / 1000
    val expired = expiresAt != null && expiresAt <= nowSeconds
    val endsSoon = expiresAt != null && expiresAt - nowSeconds < RENEW_BEFORE_SECONDS
    val daysLeft = expiresAt?.let { TimeUnit.SECONDS.toDays(it - nowSeconds).toInt().coerceAtLeast(0) }
    val trafficLimit = (if (found) account.trafficLimit else subscription?.total)?.takeIf { it > 0 }
    val trafficUsed = if (found) account.trafficUsed else subscription?.let { (it.upload ?: 0) + (it.download ?: 0) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // An active plan is shown by name; "Ваша подписка" only when there is none.
            val activePlan = when {
                expired -> null
                isFree -> stringResource(R.string.plan_free)
                planName != null -> stringResource(R.string.plan_title, planName)
                else -> null
            }
            Text(activePlan ?: stringResource(R.string.your_subscription), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val details = when {
                expired -> stringResource(R.string.plan_expired)
                expiresAt != null -> {
                    val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(expiresAt * 1000))
                    stringResource(R.string.plan_until, date) + " · " +
                        stringResource(R.string.plan_days_left, pluralStringResource(R.plurals.days, daysLeft ?: 0, daysLeft ?: 0))
                }
                isFree -> null
                else -> stringResource(R.string.plan_unlimited)
            }
            details?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (expired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trafficLimit != null && trafficUsed != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (trafficUsed.toFloat() / trafficLimit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.traffic_used, context.formatBytes(trafficUsed), context.formatBytes(trafficLimit)) +
                        " · " + stringResource(R.string.traffic_left, context.formatBytes((trafficLimit - trafficUsed).coerceAtLeast(0))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (trafficUsed != null && trafficUsed > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.traffic_used_only, context.formatBytes(trafficUsed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isFree || endsSoon) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onRenew) {
                    Text(stringResource(if (isFree) R.string.action_buy_plan else R.string.action_renew))
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    info: UpdateInfo,
    progress: UpdateProgress?,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.update_available, info.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            info.changelog?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 36.dp, top = 6.dp))
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                val statusLabel = when {
                    progress.fraction != null && progress.totalBytes > 0L -> {
                        val pct = (progress.fraction * 100f).toInt().coerceIn(0, 100)
                        stringResource(
                            R.string.update_downloading_progress,
                            pct,
                            context.formatBytes(progress.downloadedBytes),
                            context.formatBytes(progress.totalBytes),
                        )
                    }
                    progress.downloadedBytes > 0L ->
                        stringResource(R.string.update_downloading_bytes, context.formatBytes(progress.downloadedBytes))
                    else -> stringResource(R.string.update_downloading)
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                val fraction = progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel)) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                }
            }
        }
    }
}

@Composable
private fun AnnounceCard(text: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Campaign, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = rememberLinkified(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickActions(
    links: List<AppLink>,
    onOpen: (String) -> Unit,
    onSubscription: () -> Unit,
    onSupport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        links.forEach { link ->
            val isSub = link.type == "subscription" || link.type == "website"
            QuickAction(
                label = if (isSub) stringResource(R.string.link_subscription) else (link.title ?: when (link.type) {
                    "bot" -> stringResource(R.string.link_bot)
                    else -> link.url.substringAfter("://").substringBefore('/')
                }),
                onClick = { if (isSub) onSubscription() else onOpen(link.url) },
            ) {
                when {
                    isSub -> Icon(Icons.Rounded.Key, null)
                    link.type == "bot" -> Icon(painterResource(R.drawable.ic_telegram), null)
                    else -> Icon(Icons.Rounded.Link, null)
                }
            }
        }
        QuickAction(label = stringResource(R.string.support_title), onClick = onSupport) {
            Icon(Icons.Rounded.SupportAgent, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionSheet(
    subscription: SubscriptionInfo?,
    account: AccountStatus?,
    botUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val url = subscription?.url?.takeIf { it.isNotBlank() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Text(
                    text = stringResource(R.string.subscription_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (url != null) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .background(Color.White, RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCode(text = url, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.subscription_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(url))
                                Toast.makeText(context, R.string.subscription_link_copied, Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.subscription_copy_link), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(url))
                        Toast.makeText(context, R.string.subscription_link_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_copy_link))
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { context.openUrl(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_open_browser))
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { context.openUrl(botUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_telegram), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_renew_bot))
                }
            } else {
                Text(
                    text = stringResource(R.string.subscription_no_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                Button(
                    onClick = { context.openUrl(botUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_telegram), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_get_bot))
                }
            }
        }
    }
}

/** Opens the list of Telegram proxies (from the API). */
@Composable
private fun TelegramProxiesButton(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_telegram), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tg_proxies_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.tg_proxies_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RowScope.QuickAction(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun ModeSegmentedSwitch(
    activeMode: String,
    onModeChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    freeAccessAllowed: Boolean = true,
    onLockedFreeAccessClick: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .height(IntrinsicSize.Min),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isRegular = activeMode == "regular"
            // Mode 1: Основной
            Surface(
                onClick = { if (enabled) onModeChange("regular") },
                shape = RoundedCornerShape(10.dp),
                color = if (isRegular) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isRegular) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mode_regular),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isRegular) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Mode 2: Свободный доступ
            Surface(
                onClick = {
                    if (!freeAccessAllowed) {
                        onLockedFreeAccessClick()
                    } else if (enabled) {
                        onModeChange("free_access")
                    }
                },
                shape = RoundedCornerShape(10.dp),
                color = if (!isRegular && freeAccessAllowed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = when {
                    !freeAccessAllowed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    !isRegular -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (!freeAccessAllowed) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.mode_free_access),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (!isRegular && freeAccessAllowed) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun FreeAccessCard(
    connected: Boolean,
    config: FreeAccessConfig? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.free_access_status),
                    style = MaterialTheme.typography.titleMedium,
                )
                val hint = when {
                    connected -> stringResource(R.string.free_access_connected)
                    config == null -> stringResource(R.string.free_access_hint)
                    !config.hasRealVKCallLink() -> "Требуется ссылка на звонок (нажмите для настройки)"
                    config.mode == TunnelMode.SRTP_WRAP_A && config.serverPassword.isBlank() ->
                        "Нет пароля шлюза (обновите подписку или ключ)"
                    else -> "Шлюз настроен · Нажмите для параметров"
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun HomeMockView(
    activeMode: String = "regular",
    state: VpnController.State = VpnController.State.Idle,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            ModeSegmentedSwitch(
                activeMode = activeMode,
                onModeChange = {},
                enabled = true,
            )
            Spacer(Modifier.height(20.dp))
            StatusHeader(state)
            Spacer(Modifier.height(20.dp))
            ConnectButton(
                state = state,
                onClick = {},
            )
            Spacer(Modifier.height(24.dp))
            if (activeMode == "regular") {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Финляндия (Хельсинки)", style = MaterialTheme.typography.titleMedium)
                            Text("Автовыбор самого быстрого", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("14 мс", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            } else {
                FreeAccessCard(
                    connected = state is VpnController.State.Connected,
                    onClick = {},
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("Тариф «Селюк Премиум»", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Срок действия: 28 дней · Безлимитный трафик", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Preview(name = "Selyk Premium Dark", showBackground = true)
@Composable
fun HomeScreenPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        HomeMockView(activeMode = "regular", state = VpnController.State.Idle)
    }
}

@Preview(name = "Selyk Premium Connected", showBackground = true)
@Composable
fun HomeScreenConnectedPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        HomeMockView(
            activeMode = "regular",
            state = VpnController.State.Connected(System.currentTimeMillis() - 120_000, "fi-1")
        )
    }
}

@Preview(name = "Selyk Premium Light", showBackground = true)
@Composable
fun HomeScreenLightPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = false) {
        HomeMockView(activeMode = "regular", state = VpnController.State.Idle)
    }
}

@Preview(name = "Cool Graphite Dark", showBackground = true)
@Composable
fun HomeScreenGraphitePreview() {
    SelykVpnTheme(palette = "graphite", darkTheme = true) {
        HomeMockView(activeMode = "regular", state = VpnController.State.Idle)
    }
}

@Preview(name = "Free Access Mode", showBackground = true)
@Composable
fun HomeScreenFreeAccessPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        HomeMockView(
            activeMode = "free_access",
            state = VpnController.State.Connected(System.currentTimeMillis() - 45_000, "free_access")
        )
    }
}

@Preview(name = "Compact 320dp FontScale 1.3", widthDp = 320, heightDp = 640, fontScale = 1.3f, showBackground = true)
@Composable
fun HomeScreenCompactLargeFontPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        HomeMockView(activeMode = "free_access", state = VpnController.State.Idle)
    }
}

@Preview(name = "Tablet 840dp", widthDp = 840, heightDp = 600, showBackground = true)
@Composable
fun HomeScreenTabletPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.weight(1f)) {
                HomeMockView(activeMode = "regular", state = VpnController.State.Idle)
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Серверы", style = MaterialTheme.typography.titleMedium)
                    Text("🇫🇮 Финляндия · 14 мс", style = MaterialTheme.typography.bodyMedium)
                    Text("⚡ Автовыбор (Все операторы)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
