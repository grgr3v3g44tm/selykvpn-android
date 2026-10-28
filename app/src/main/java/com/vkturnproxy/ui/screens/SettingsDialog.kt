package com.vkturnproxy.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vkturnproxy.BuildConfig
import com.vkturnproxy.auth.VKCookieStore
import com.vkturnproxy.log.AppLog
import com.vkturnproxy.ui.theme.*

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    val isLoggingEnabled by AppLog.isEnabledFlow.collectAsState()
    var hasValidVKCookies by remember { mutableStateOf(VKCookieStore.hasValidLogin()) }
    var expiryText by remember { mutableStateOf(VKCookieStore.getFormattedExpiry()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark.copy(alpha = 0.90f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(PrimaryBlue.copy(alpha = 0.15f), shape = RoundedCornerShape(19.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Настройки",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Section 1: Diagnostics and Logging
                    Text(
                        text = "ДИАГНОСТИКА И ЛОГИ",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Запись логов (AppLog)",
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isLoggingEnabled)
                                            "Логи записываются в память для отправки разработчику"
                                        else
                                            "Выключено по умолчанию для экономии памяти и батареи",
                                        color = if (isLoggingEnabled) AccentGreen else TextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = isLoggingEnabled,
                                    onCheckedChange = { AppLog.setEnabled(context, it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = SurfaceDark, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedButton(
                                onClick = onOpenDiagnostics,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryBlue)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Открыть журнал диагностики", fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Section 2: VK Account
                    Text(
                        text = "АККАУНТ И ЗВОНКИ VK",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (hasValidVKCookies) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = if (hasValidVKCookies) AccentGreen else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (hasValidVKCookies) "Сессия VK привязана" else "Сессия VK не привязана",
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (hasValidVKCookies) "Сессия активна: ${expiryText ?: "OK"}" else "Войдите через диалог привязки звонка",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (hasValidVKCookies) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        VKCookieStore.clearCookies()
                                        hasValidVKCookies = false
                                        Toast.makeText(context, "Сессия VK сброшена", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                                ) {
                                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentRed)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Выйти из аккаунта VK", fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Section 3: About
                    Text(
                        text = "О ПРИЛОЖЕНИИ",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("VK TURN Proxy", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Версия ${BuildConfig.VERSION_NAME} (сборка 40)", color = TextSecondary, fontSize = 12.sp)
                                }
                                Text("libwgturn.so", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    onDismiss()
                                    onCheckUpdate()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Проверить обновления", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
