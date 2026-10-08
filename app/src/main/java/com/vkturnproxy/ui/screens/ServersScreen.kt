package com.vkturnproxy.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.parser.ConfigParser
import com.vkturnproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    servers: List<ServerConfig>,
    activeServerId: String?,
    onSelectServer: (ServerConfig) -> Unit,
    onEditServer: (ServerConfig) -> Unit,
    onDeleteServer: (String) -> Unit,
    onAddServer: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы", color = TextPrimary, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
                actions = {
                    IconButton(onClick = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        importText = clip
                        showImportDialog = true
                    }) {
                        Icon(Icons.Default.AddLink, contentDescription = "Импорт", tint = PrimaryBlue)
                    }
                    IconButton(onClick = {
                        onAddServer(ServerConfig(name = "Новый сервер"))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить", tint = PrimaryBlue)
                    }
                }
            )
        },
        containerColor = BgDark
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(servers, key = { it.id }) { server ->
                val isActive = server.id == activeServerId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectServer(server) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) SurfaceVariantDark else SurfaceDark
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) PrimaryBlue else BgDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.Check else Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = if (isActive) Color.Black else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = server.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "${server.mode.name} • ${server.peerAddress.ifEmpty { "Звонок VK" }}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row {
                            IconButton(onClick = { onEditServer(server) }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Редактировать",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (servers.size > 1) {
                                IconButton(onClick = { onDeleteServer(server.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Удалить",
                                        tint = AccentRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Импорт конфигурации", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Вставьте ссылку vkturnproxy:// или WireGuard .conf / JSON",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("vkturnproxy://import?data=...", color = TextSecondary.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = SurfaceVariantDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        importText = clip
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Вставить из буфера обмена")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = ConfigParser.parse(importText)
                        if (parsed != null) {
                            onAddServer(parsed)
                            showImportDialog = false
                            Toast.makeText(context, "Сервер «${parsed.name}» импортирован", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Неверный формат конфигурации", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Импортировать", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
