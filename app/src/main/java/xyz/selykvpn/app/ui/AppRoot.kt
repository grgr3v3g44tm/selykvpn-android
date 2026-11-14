package xyz.selykvpn.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import xyz.selykvpn.app.R
import xyz.selykvpn.app.isTv
import xyz.selykvpn.app.data.RouteAction
import xyz.selykvpn.app.data.SettingsImport
import xyz.selykvpn.app.data.TvPairing
import xyz.selykvpn.app.ui.common.LinkQr
import xyz.selykvpn.app.ui.common.QrLinkDialog
import xyz.selykvpn.app.ui.common.openUrl
import xyz.selykvpn.app.ui.home.HomeScreen
import xyz.selykvpn.app.ui.onboarding.OnboardingScreen
import xyz.selykvpn.app.ui.scan.ScanScreen
import xyz.selykvpn.app.ui.settings.ConnectionLogScreen
import xyz.selykvpn.app.ui.settings.RoutingScreen
import xyz.selykvpn.app.ui.settings.SettingsScreen
import xyz.selykvpn.app.ui.settings.SplitTunnelScreen
import xyz.selykvpn.app.ui.share.ShareScreen
import xyz.selykvpn.app.ui.tv.TvOnboardingScreen
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.serialization.Serializable

@Serializable data object OnboardingKey : NavKey
@Serializable data object ChangeKeyKey : NavKey
@Serializable data object ScanKey : NavKey
@Serializable data object HomeKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object SplitTunnelKey : NavKey
@Serializable data object ShareKey : NavKey
@Serializable data object RoutingKey : NavKey
@Serializable data object ConnectionLogKey : NavKey

@Composable
fun AppRoot(viewModel: AppViewModel, onConnect: () -> Unit) {
    val context = LocalContext.current
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val freeAccessConfig by viewModel.freeAccessConfig.collectAsStateWithLifecycle()
    val captcha by viewModel.captcha.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val keyError by viewModel.keyError.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val tvSend by viewModel.tvSend.collectAsStateWithLifecycle()
    val qrLink by LinkQr.link.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val tv = remember { context.isTv() }

    val hasKey = subscription != null || freeAccessConfig.stealthLink.isNotBlank() || freeAccessConfig.serverPassword.isNotBlank() || freeAccessConfig.wrapKeyHex.isNotBlank()
    val backStack = rememberNavBackStack(if (hasKey) HomeKey else OnboardingKey)

    // Adding or removing the key switches between onboarding and the main screen.
    LaunchedEffect(hasKey, subscription?.url, freeAccessConfig.id) {
        val root = if (hasKey) HomeKey else OnboardingKey
        val onKeyScreens = backStack.lastOrNull() == ChangeKeyKey || backStack.lastOrNull() == ScanKey
        if (backStack.firstOrNull() != root || (hasKey && onKeyScreens)) {
            backStack.add(root)
            while (backStack.size > 1) backStack.removeAt(0)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(context.getString(message.text, *message.args.toTypedArray()))
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<OnboardingKey> {
                    if (tv) TvOnboardingScreen(viewModel = viewModel, onBack = null)
                    else OnboardingScreen(viewModel = viewModel, onScan = { backStack.add(ScanKey) }, onBack = null)
                }
                entry<ChangeKeyKey> {
                    if (tv) {
                        TvOnboardingScreen(viewModel = viewModel, onBack = { backStack.removeLastOrNull() })
                    } else {
                        OnboardingScreen(
                            viewModel = viewModel,
                            onScan = { backStack.add(ScanKey) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
                entry<ScanKey> {
                    ScanScreen(
                        onResult = { text ->
                            backStack.removeLastOrNull()
                            viewModel.submitKey(text)
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<HomeKey> {
                    HomeScreen(
                        viewModel = viewModel,
                        onConnect = onConnect,
                        onOpenSettings = { backStack.add(SettingsKey) },
                    )
                }
                entry<SettingsKey> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() },
                        onSplitTunnel = { backStack.add(SplitTunnelKey) },
                        onShare = { backStack.add(ShareKey) },
                        onRouting = { backStack.add(RoutingKey) },
                        onConnectionLog = { backStack.add(ConnectionLogKey) },
                        onChangeKey = { backStack.add(ChangeKeyKey) },
                        onConnectTv = { backStack.add(ScanKey) },
                    )
                }
                entry<SplitTunnelKey> {
                    SplitTunnelScreen(viewModel = viewModel, onBack = { backStack.removeLastOrNull() })
                }
                entry<ShareKey> {
                    ShareScreen(viewModel = viewModel, onBack = { backStack.removeLastOrNull() })
                }
                entry<RoutingKey> {
                    RoutingScreen(viewModel = viewModel, onBack = { backStack.removeLastOrNull() })
                }
                entry<ConnectionLogKey> {
                    ConnectionLogScreen(viewModel = viewModel, onBack = { backStack.removeLastOrNull() })
                }
            },
        )

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }

    when (importState) {
        ImportState.Loading -> LoadingDialog(stringResource(R.string.importing))
        is ImportState.ConfirmReplace -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.replace_key_title)) },
            text = { Text(stringResource(R.string.replace_key_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmReplace) { Text(stringResource(R.string.action_replace)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.action_cancel)) } },
        )
        ImportState.Idle -> Unit
    }

    tvSend?.let { code ->
        AlertDialog(
            onDismissRequest = viewModel::cancelTvSend,
            title = { Text(stringResource(R.string.tv_send_title)) },
            text = { Text(stringResource(R.string.tv_send_text, TvPairing.display(code))) },
            confirmButton = { TextButton(onClick = viewModel::confirmTvSend) { Text(stringResource(R.string.tv_send_action)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelTvSend) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    qrLink?.let { link -> QrLinkDialog(link.url, link.title, onDismiss = LinkQr::dismiss) }

    pendingImport?.let { file ->
        ImportDialog(
            file = file,
            onReplace = { viewModel.applyImport(replace = true) },
            onMerge = { viewModel.applyImport(replace = false) },
            onDismiss = viewModel::dismissImport,
        )
    }

    keyError?.let { error ->
        val offerBot = error == R.string.error_foreign_key || error == R.string.error_not_found
        AlertDialog(
            onDismissRequest = viewModel::dismissKeyError,
            text = { Text(stringResource(error)) },
            confirmButton = {
                if (offerBot) {
                    TextButton(onClick = {
                        viewModel.dismissKeyError()
                        context.openUrl(config.telegramBotUrl)
                    }) { Text(stringResource(R.string.action_open_bot)) }
                } else {
                    TextButton(onClick = viewModel::dismissKeyError) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = if (offerBot) {
                { TextButton(onClick = viewModel::dismissKeyError) { Text(stringResource(R.string.action_close)) } }
            } else {
                null
            },
        )
    }

    captcha?.let { challenge ->
        CaptchaDialog(
            challenge = challenge,
            loadBitmap = viewModel::loadCaptchaBitmap,
            onSubmit = viewModel::submitCaptcha,
            onRefresh = viewModel::refreshCaptcha,
            onDismiss = viewModel::cancelCaptcha,
        )
    }
}

@Composable
private fun CaptchaDialog(
    challenge: VpnController.CaptchaChallenge,
    loadBitmap: suspend (String) -> Bitmap?,
    onSubmit: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by remember(challenge.imageUrl) { mutableStateOf("") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var bitmap by remember(challenge.imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(challenge.imageUrl) { mutableStateOf(true) }

    LaunchedEffect(challenge.imageUrl, refreshNonce) {
        loading = true
        bitmap = loadBitmap(challenge.imageUrl)
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.free_access_captcha_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    when {
                        loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.free_access_captcha_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray,
                            )
                        }
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        else -> Text(
                            stringResource(R.string.free_access_captcha_load_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = { Text(stringResource(R.string.free_access_captcha_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        refreshNonce++
                        onRefresh()
                    }) {
                        Text(stringResource(R.string.free_access_captcha_refresh))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.free_access_captcha_cancel))
                        }
                        Button(
                            onClick = { onSubmit(answer) },
                            enabled = answer.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.free_access_captcha_submit))
                        }
                    }
                }
            }
        }
    }
}

/** "Replace your settings or add to them?" for a settings file. */
@Composable
private fun ImportDialog(file: SettingsImport, onReplace: () -> Unit, onMerge: () -> Unit, onDismiss: () -> Unit) {
    val title: String
    val text: String
    when (file) {
        is SettingsImport.Routing -> {
            val r = file.settings
            val total = RouteAction.entries.sumOf { r.rules(it).size }
            title = stringResource(R.string.import_routing_title)
            text = stringResource(R.string.import_routing_text, total, r.direct.size, r.proxy.size, r.block.size)
        }
        is SettingsImport.Apps -> {
            title = stringResource(R.string.import_apps_title)
            text = stringResource(R.string.import_apps_text, file.apps.excluded.size)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                listOfNotNull(
                    text,
                    if (file.skipped > 0) stringResource(R.string.import_skipped, file.skipped) else null,
                    stringResource(R.string.import_question),
                ).joinToString("\n\n"),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onMerge) { Text(stringResource(R.string.import_merge)) }
                TextButton(onClick = onReplace) { Text(stringResource(R.string.import_replace)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LoadingDialog(text: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
