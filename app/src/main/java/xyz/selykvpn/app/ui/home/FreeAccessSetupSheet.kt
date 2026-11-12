package xyz.selykvpn.app.ui.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val vkAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
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
            isCreatingCall = isCreatingCall,
            onCreateCallViaVk = {
                if (VKCookieStore.hasValidLogin()) {
                    isCreatingCall = true
                    scope.launch {
                        val res = VKCallHelper.createCallFast(context)
                        isCreatingCall = false
                        when (res) {
                            is FastCallResult.Success -> {
                                VKCookieStore.setEnabled(true)
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
            onManualLinkClick = { showManualLinkDialog = true },
            onUnlinkCall = {
                val updated = currentConfig.copy(
                    stealthLink = "",
                    needsStealthLink = true,
                    useSessionAuth = false,
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
    isCreatingCall: Boolean,
    onCreateCallViaVk: () -> Unit,
    onManualLinkClick: () -> Unit,
    onUnlinkCall: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val hasCallLink = config.hasRealVKCallLink()
    val missingWrapAPass = config.mode == TunnelMode.SRTP_WRAP_A && config.serverPassword.isBlank()
    val ready = hasCallLink && !missingWrapAPass

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                            "Защищённый звонок привязан. Трафик автоматически направляется через доверенный шлюз."
                        else ->
                            "Для работы шлюза войдите через VK ID или вставьте ссылку звонка вручную."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusTextColor,
                )
            }
        }

        // Action buttons for VK Call (single VK button + manual paste + unlink)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

            if (hasCallLink) {
                TextButton(
                    onClick = onUnlinkCall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.free_access_unlink_call), color = MaterialTheme.colorScheme.error)
                }
            }

            OutlinedButton(
                onClick = {
                    val pkgUri = Uri.parse("package:${context.packageName}")
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.free_access_battery_title))
            }
            Text(
                text = stringResource(R.string.free_access_battery_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Preview(name = "Phone 360dp - Ready", widthDp = 360, showBackground = true)
@Composable
fun FreeAccessSetupSheetReadyPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "https://vk.ru/call/join/abc123",
                    serverPassword = "p_secret",
                    useUDP = false,
                ),
                isCreatingCall = false,
                onCreateCallViaVk = {},
                onManualLinkClick = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}

@Preview(name = "Small 320dp FontScale 1.3 - Need Call", widthDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
fun FreeAccessSetupSheetCompactLargeFontPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "",
                    serverPassword = "p_secret",
                    useUDP = false,
                ),
                isCreatingCall = false,
                onCreateCallViaVk = {},
                onManualLinkClick = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}

@Preview(name = "Tablet 600dp Light", widthDp = 600, showBackground = true)
@Composable
fun FreeAccessSetupSheetTabletLightPreview() {
    SelykVpnTheme(palette = "selyk", darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            FreeAccessSetupSheetContent(
                config = FreeAccessConfig(
                    stealthLink = "",
                    serverPassword = "p_secret",
                    useUDP = false,
                ),
                isCreatingCall = false,
                onCreateCallViaVk = {},
                onManualLinkClick = {},
                onUnlinkCall = {},
                onDone = {},
            )
        }
    }
}
