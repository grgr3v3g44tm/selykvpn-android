package xyz.selykvpn.app.ui.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.selykvpn.app.R
import xyz.selykvpn.app.auth.FastCallResult
import xyz.selykvpn.app.auth.VKAuthActivity
import xyz.selykvpn.app.auth.VKCallHelper
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.openUrl
import xyz.selykvpn.app.ui.theme.SelykVpnTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeAccessSetupSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val currentConfig by viewModel.freeAccessConfig.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        VKCookieStore.init(context)
    }

    var isCreatingCall by remember { mutableStateOf(false) }
    var showManualLinkDialog by remember { mutableStateOf(false) }
    var hasVkSession by remember(currentConfig.useSessionAuth) {
        mutableStateOf(VKCookieStore.hasStoredSession())
    }

    val vkAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasVkSession = VKCookieStore.hasStoredSession()
        if (result.resultCode == Activity.RESULT_OK) {
            val callLink = result.data?.getStringExtra(VKAuthActivity.EXTRA_CALL_LINK).orEmpty()
            VKCookieStore.setEnabled(true)
            val updated = if (callLink.isNotBlank()) {
                currentConfig.copy(
                    stealthLink = callLink,
                    needsStealthLink = false,
                    useSessionAuth = true,
                )
            } else {
                currentConfig.copy(useSessionAuth = true)
            }
            viewModel.updateFreeAccessConfig(updated)
            if (callLink.isNotBlank()) {
                Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        FreeAccessSetupSheetContent(
            config = currentConfig,
            hasVkSession = hasVkSession,
            isCreatingCall = isCreatingCall,
            onToggleUseVkAccount = { enabled ->
                VKCookieStore.setEnabled(enabled)
                hasVkSession = VKCookieStore.hasStoredSession()
                viewModel.updateFreeAccessConfig(currentConfig.copy(useSessionAuth = enabled))
            },
            onSignOutVk = {
                VKCookieStore.clearSession()
                hasVkSession = false
            },
            onCreateCallViaVk = {
                if (VKCookieStore.hasValidLogin()) {
                    isCreatingCall = true
                    scope.launch {
                        val res = VKCallHelper.createCallFast(context)
                        isCreatingCall = false
                        when (res) {
                            is FastCallResult.Success -> {
                                val updated = currentConfig.copy(
                                    stealthLink = res.joinLink,
                                    needsStealthLink = false,
                                    useSessionAuth = true,
                                )
                                viewModel.updateFreeAccessConfig(updated)
                                Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
                            }
                            is FastCallResult.Error -> {
                                if (res.isAccountBanned) {
                                    VKCookieStore.clearSession()
                                    hasVkSession = false
                                    Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                                } else {
                                    vkAuthLauncher.launch(VKAuthActivity.createCallIntent(context))
                                }
                            }
                        }
                    }
                } else {
                    vkAuthLauncher.launch(VKAuthActivity.createCallIntent(context))
                }
            },
            onOpenVkCallsBrowser = {
                context.openUrl("https://vk.ru/calls")
            },
            onManualLinkClick = { showManualLinkDialog = true },
            onToggleUdp = { useUdp ->
                viewModel.updateFreeAccessConfig(currentConfig.copy(useUDP = useUdp))
            },
            onUnlinkCall = {
                val updated = currentConfig.copy(
                    stealthLink = "",
                    needsStealthLink = true,
                )
                viewModel.updateFreeAccessConfig(updated)
            },
            onDone = onDismiss,
        )
    }

    if (showManualLinkDialog) {
        var inputLink by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualLinkDialog = false },
            title = { Text("Ссылка на звонок VK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Вставьте ссылку вида https://vk.com/call/join/... или https://vk.ru/call/join/...",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = inputLink,
                        onValueChange = { inputLink = it },
                        placeholder = { Text("https://vk.ru/call/join/...") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                            if (clip.isNotBlank()) inputLink = clip
                        }
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Вставить из буфера")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = inputLink.trim()
                        if (trimmed.contains("call/join/")) {
                            val updated = currentConfig.withCallLink(trimmed)
                            viewModel.updateFreeAccessConfig(updated)
                            showManualLinkDialog = false
                            Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ссылка должна содержать call/join/...", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Привязать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualLinkDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun FreeAccessSetupSheetContent(
    config: FreeAccessConfig,
    hasVkSession: Boolean,
    isCreatingCall: Boolean,
    onToggleUseVkAccount: (Boolean) -> Unit,
    onSignOutVk: () -> Unit,
    onCreateCallViaVk: () -> Unit,
    onOpenVkCallsBrowser: () -> Unit,
    onManualLinkClick: () -> Unit,
    onToggleUdp: (Boolean) -> Unit,
    onUnlinkCall: () -> Unit,
    onDone: () -> Unit,
) {
    val hasCallLink = config.hasRealVKCallLink()
    val missingWrapAPass = config.mode == TunnelMode.SRTP_WRAP_A && config.serverPassword.isBlank()
    val useVkAccount = config.useSessionAuth

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Column {
            Text(
                text = stringResource(R.string.mode_free_access),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.free_access_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Call & gateway status card
        val ready = hasCallLink && !missingWrapAPass
        val statusBg = when {
            ready -> MaterialTheme.colorScheme.tertiaryContainer
            hasCallLink && missingWrapAPass -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val statusAccent = when {
            ready -> MaterialTheme.colorScheme.onTertiaryContainer
            hasCallLink && missingWrapAPass -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.primary
        }
        val statusTextColor = when {
            ready -> MaterialTheme.colorScheme.onTertiaryContainer
            hasCallLink && missingWrapAPass -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = statusBg,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = when {
                            ready -> Icons.Rounded.CheckCircle
                            missingWrapAPass -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.Warning
                        },
                        contentDescription = null,
                        tint = statusAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = when {
                            ready -> "Шлюз готов к работе"
                            missingWrapAPass -> "Нет пароля сервера WRAP-A"
                            else -> "Требуется ссылка на звонок"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusAccent,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        missingWrapAPass ->
                            "Пароль WRAP-A не получен. Обновите подписку (Потянуть / Настройки -> Обновить) или импортируйте ключ из Telegram-бота."
                        hasCallLink ->
                            "Защищённый звонок привязан. Трафик автоматически направляется через прямой шлюз."
                        useVkAccount ->
                            "Войдите через VK ID и создайте звонок или вставьте готовую ссылку вручную."
                        else ->
                            "Создайте звонок через браузер VK и вставьте ссылку вида vk.ru/call/join/..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusTextColor,
                )
            }
        }

        // Toggle: Use VK Account
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.free_access_use_vk_account),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                if (useVkAccount) R.string.free_access_use_vk_account_on
                                else R.string.free_access_use_vk_account_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useVkAccount,
                        onCheckedChange = onToggleUseVkAccount,
                    )
                }

                if (useVkAccount) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (hasVkSession) Icons.Rounded.VerifiedUser else Icons.Rounded.PersonOutline,
                                contentDescription = null,
                                tint = if (hasVkSession) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (hasVkSession) R.string.free_access_vk_session_active
                                    else R.string.free_access_vk_session_none
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasVkSession) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hasVkSession) {
                            TextButton(
                                onClick = onSignOutVk,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.free_access_vk_logout),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Action buttons for VK Call
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (useVkAccount) {
                Button(
                    onClick = onCreateCallViaVk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isCreatingCall,
                ) {
                    if (isCreatingCall) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Создание звонка…")
                    } else {
                        Icon(Icons.Rounded.VideoCall, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (hasCallLink) R.string.free_access_create_call_new
                                else R.string.free_access_create_call_auth
                            ),
                        )
                    }
                }
            } else {
                Button(
                    onClick = onOpenVkCallsBrowser,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.free_access_create_call_browser))
                }
            }

            OutlinedButton(
                onClick = onManualLinkClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.free_access_paste_call))
            }

            // Transport mode toggle (TCP 3478 default vs UDP)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.free_access_transport_udp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.free_access_transport_udp_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.useUDP,
                        onCheckedChange = onToggleUdp,
                    )
                }
            }

            if (hasCallLink) {
                TextButton(
                    onClick = onUnlinkCall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.free_access_unlink_call), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        FilledTonalButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.free_access_done))
        }
    }
}

@Preview(name = "Phone 360dp - VK Auth ON", widthDp = 360, showBackground = true)
@Composable
fun FreeAccessSetupSheetVkOnPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "https://vk.ru/call/join/abc123",
                    serverPassword = "p_secret",
                    useSessionAuth = true,
                    useUDP = false,
                ),
                hasVkSession = true,
                isCreatingCall = false,
                onToggleUseVkAccount = {},
                onSignOutVk = {},
                onCreateCallViaVk = {},
                onOpenVkCallsBrowser = {},
                onManualLinkClick = {},
                onToggleUdp = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}

@Preview(name = "Small 320dp FontScale 1.3 - VK Auth OFF", widthDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
fun FreeAccessSetupSheetCompactLargeFontPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "",
                    serverPassword = "",
                    useSessionAuth = false,
                    useUDP = false,
                ),
                hasVkSession = false,
                isCreatingCall = false,
                onToggleUseVkAccount = {},
                onSignOutVk = {},
                onCreateCallViaVk = {},
                onOpenVkCallsBrowser = {},
                onManualLinkClick = {},
                onToggleUdp = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}

@Preview(name = "Tablet 600dp Light - VK Auth ON", widthDp = 600, showBackground = true)
@Composable
fun FreeAccessSetupSheetTabletLightPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "",
                    serverPassword = "p_secret",
                    useSessionAuth = true,
                    useUDP = false,
                ),
                hasVkSession = false,
                isCreatingCall = false,
                onToggleUseVkAccount = {},
                onSignOutVk = {},
                onCreateCallViaVk = {},
                onOpenVkCallsBrowser = {},
                onManualLinkClick = {},
                onToggleUdp = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}
