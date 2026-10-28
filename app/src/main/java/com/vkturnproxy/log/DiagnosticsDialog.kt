package com.vkturnproxy.log

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by AppLog.logsFlow.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var filterTag by remember { mutableStateOf<String?>(null) }

    val filteredLogs = remember(logs, filterLevel, filterTag) {
        logs.filter { entry ->
            val matchLevel = filterLevel == null || entry.level == filterLevel
            val matchTag = filterTag == null || entry.tag.equals(filterTag, ignoreCase = true)
            matchLevel && matchTag
        }
    }

    // Auto-scroll to bottom on new items
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark.copy(alpha = 0.90f))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
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
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Журнал диагностики",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    "Событий в буфере: ${logs.size}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterLevel == null && filterTag == null,
                            onClick = {
                                filterLevel = null
                                filterTag = null
                            },
                            label = { Text("Все", fontSize = 12.sp) }
                        )

                        FilterChip(
                            selected = filterLevel == LogLevel.ERROR,
                            onClick = {
                                filterLevel = if (filterLevel == LogLevel.ERROR) null else LogLevel.ERROR
                            },
                            label = { Text("Ошибки", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentRed.copy(alpha = 0.25f),
                                selectedLabelColor = AccentRed
                            )
                        )

                        FilterChip(
                            selected = filterTag == "VKAuth" || filterTag == "VKCallHelper",
                            onClick = {
                                filterTag = if (filterTag != null) null else "VKCallHelper"
                            },
                            label = { Text("VK Звонки", fontSize = 12.sp) }
                        )

                        FilterChip(
                            selected = filterTag == "VkTurnVpnService",
                            onClick = {
                                filterTag = if (filterTag == "VkTurnVpnService") null else "VkTurnVpnService"
                            },
                            label = { Text("VPN Туннель", fontSize = 12.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Log items box
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = BgDark
                    ) {
                        if (filteredLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "События пока отсутствуют",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredLogs) { entry ->
                                    val levelColor = when (entry.level) {
                                        LogLevel.DEBUG -> TextSecondary
                                        LogLevel.INFO -> PrimaryBlue
                                        LogLevel.WARN -> AccentAmber
                                        LogLevel.ERROR -> AccentRed
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            entry.formattedTime,
                                            color = TextSecondary.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.width(76.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "[${entry.tag}]",
                                            color = levelColor,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.widthIn(max = 120.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            entry.message,
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Actions bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { AppLog.copyToClipboard(context) },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Копировать", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { AppLog.share(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Поделиться", fontSize = 13.sp)
                        }

                        IconButton(
                            onClick = { AppLog.clear() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
