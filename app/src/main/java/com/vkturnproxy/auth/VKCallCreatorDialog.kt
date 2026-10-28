package com.vkturnproxy.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vkturnproxy.ui.theme.*

private const val TAG = "VKCallCreator"

/**
 * Clean modal dialog implementing iOS VKCallCreationGate pattern:
 * 1. Shows account login state (remixsid + p).
 * 2. If logged in: 1-tap "Создать автоматически через VK" (VKOAuth + calls.start).
 * 3. If not logged in: "Войти в аккаунт VK" (VKAuth on https://vk.ru/).
 * 4. 1-tap paste from clipboard & manual link input fallback.
 */
@Composable
fun VKCallCreatorDialog(
    onDismiss: () -> Unit,
    onCallCreated: (joinLink: String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var manualLinkInput by remember { mutableStateOf("") }
    var hasLogin by remember { mutableStateOf(VKCookieStore.hasValidLogin()) }
    var expiryText by remember { mutableStateOf(VKCookieStore.getFormattedExpiry()) }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasLogin = VKCookieStore.hasValidLogin()
        expiryText = VKCookieStore.getFormattedExpiry()

        if (result.resultCode == Activity.RESULT_OK) {
            val link = result.data?.getStringExtra(VKAuthActivity.EXTRA_CALL_LINK)
            if (!link.isNullOrBlank()) {
                onCallCreated(link)
                Toast.makeText(context, "Звонок успешно создан и привязан!", Toast.LENGTH_SHORT).show()
                onDismiss()
                return@rememberLauncherForActivityResult
            }

            val cookieHeader = result.data?.getStringExtra(VKAuthActivity.EXTRA_COOKIE_HEADER)
            if (!cookieHeader.isNullOrBlank()) {
                Toast.makeText(context, "Вход выполнен! Теперь нажмите «Создать звонок»", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark.copy(alpha = 0.85f))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .padding(22.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(PrimaryBlue.copy(alpha = 0.15f), shape = RoundedCornerShape(21.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Привязка звонка VK",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "Для работы туннеля необходима ссылка на звонок VK. Выберите удобный способ:",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Account status card (1-to-1 with VKCallCreationGate)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (hasLogin) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (hasLogin) AccentGreen else AccentAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (hasLogin) "Аккаунт VK привязан" else "Вход в аккаунт VK не выполнен",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    if (hasLogin) "Сессия активна до $expiryText" else "Необходим для авто-создания ссылки",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Option 1 (Primary): Automatic creation via VK OAuth & API (or login first)
                    if (hasLogin) {
                        Button(
                            onClick = {
                                authLauncher.launch(VKAuthActivity.createCallIntent(context))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.AutoMode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Создать звонок автоматически", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                authLauncher.launch(VKAuthActivity.createLoginIntent(context))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Войти в аккаунт VK", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 2: Paste from clipboard
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                            val foundLink = clipText.lines().map { it.trim() }.firstOrNull { it.contains("call/join/") }
                            if (!foundLink.isNullOrBlank()) {
                                onCallCreated(foundLink)
                                Toast.makeText(context, "Ссылка успешно привязана из буфера!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "В буфере нет ссылки на звонок (нужна ссылка вида https://vk.ru/call/join/...)", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp), tint = PrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Вставить ссылку из буфера", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 3: Open official VK app / web to create call
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.ru/calls"))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Не удалось открыть браузер/VK", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Создать в приложении VK / браузере", fontWeight = FontWeight.Normal, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Option 4: Manual link entry field
                    OutlinedTextField(
                        value = manualLinkInput,
                        onValueChange = { manualLinkInput = it },
                        placeholder = { Text("https://vk.ru/call/join/...", color = TextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (manualLinkInput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val clean = manualLinkInput.trim()
                                if (clean.contains("call/join/")) {
                                    onCallCreated(clean)
                                    Toast.makeText(context, "Ссылка успешно привязана!", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Ссылка должна содержать vk.ru/call/join/...", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Применить введенную ссылку", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
