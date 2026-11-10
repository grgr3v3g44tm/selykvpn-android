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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.selykvpn.app.R
import xyz.selykvpn.app.auth.VKAuthActivity
import xyz.selykvpn.app.auth.VKCallHelper
import xyz.selykvpn.app.auth.VKCookieStore
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.ui.AppViewModel

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

    var stealthLink by remember(currentConfig) { mutableStateOf(currentConfig.stealthLink) }
    var isCreatingCall by remember { mutableStateOf(false) }
    var showManualLinkDialog by remember { mutableStateOf(false) }

    val vkAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val callLink = result.data?.getStringExtra(VKAuthActivity.EXTRA_CALL_LINK).orEmpty()
            if (callLink.isNotBlank()) {
                stealthLink = callLink
                val updated = currentConfig.copy(
                    stealthLink = callLink,
                    needsStealthLink = false
                )
                viewModel.updateFreeAccessConfig(updated)
                Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
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

            // Call status card
            val hasCallLink = stealthLink.contains("call/join/")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (hasCallLink) Color(0xFF1B382B) else Color(0xFF3E2D18),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (hasCallLink) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (hasCallLink) Color(0xFF6BDD9A) else Color(0xFFF6AD55),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = if (hasCallLink) "Шлюз готов к работе" else "Требуется ссылка на звонок",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasCallLink) Color(0xFF6BDD9A) else Color(0xFFF6AD55),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (hasCallLink) {
                            "Защищённый звонок привязан. Трафик автоматически направляется через доверенный шлюз."
                        } else {
                            "Для работы шлюза войдите через VK ID или вставьте ссылку звонка по инструкции."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            // Action buttons for VK Call
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (VKCookieStore.hasValidLogin()) {
                            isCreatingCall = true
                            scope.launch {
                                val res = VKCallHelper.createCallFast(context)
                                isCreatingCall = false
                                when (res) {
                                    is xyz.selykvpn.app.auth.FastCallResult.Success -> {
                                        stealthLink = res.joinLink
                                        val updated = currentConfig.copy(
                                            stealthLink = res.joinLink,
                                            needsStealthLink = false
                                        )
                                        viewModel.updateFreeAccessConfig(updated)
                                        Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
                                    }
                                    is xyz.selykvpn.app.auth.FastCallResult.Error -> {
                                        vkAuthLauncher.launch(VKAuthActivity.createCallIntent(context))
                                    }
                                }
                            }
                        } else {
                            vkAuthLauncher.launch(VKAuthActivity.createCallIntent(context))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isCreatingCall,
                ) {
                    if (isCreatingCall) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Создание звонка…")
                    } else {
                        Icon(Icons.Rounded.VideoCall, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasCallLink) "Создать новый звонок через VK" else "Войти через VK и создать звонок")
                    }
                }

                OutlinedButton(
                    onClick = { showManualLinkDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Вставить ссылку звонка вручную")
                }

                if (hasCallLink) {
                    TextButton(
                        onClick = {
                            stealthLink = ""
                            val updated = currentConfig.copy(
                                stealthLink = "",
                                needsStealthLink = true
                            )
                            viewModel.updateFreeAccessConfig(updated)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отвязать звонок", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Готово")
            }
        }
    }

    if (showManualLinkDialog) {
        var inputLink by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualLinkDialog = false },
            title = { Text("Ссылка на звонок VK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Вставьте ссылку вида https://vk.com/call/join/... созданную в приложении или браузере.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = inputLink,
                        onValueChange = { inputLink = it },
                        placeholder = { Text("https://vk.com/call/join/...") },
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
                            stealthLink = trimmed
                            val updated = currentConfig.copy(
                                stealthLink = trimmed,
                                needsStealthLink = false,
                            )
                            viewModel.updateFreeAccessConfig(updated)
                            showManualLinkDialog = false
                            Toast.makeText(context, R.string.free_access_call_linked, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ссылка должна содержать vk.com/call/join/...", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Привязать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualLinkDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
