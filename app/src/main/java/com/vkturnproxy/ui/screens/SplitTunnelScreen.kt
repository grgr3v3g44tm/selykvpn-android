package com.vkturnproxy.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.vkturnproxy.service.AppInfo
import com.vkturnproxy.service.SplitTunnelManager
import com.vkturnproxy.service.SplitTunnelMode
import com.vkturnproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    splitTunnelManager: SplitTunnelManager,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(splitTunnelManager.isEnabled) }
    var mode by remember { mutableStateOf(splitTunnelManager.mode) }
    var selectedPackages by remember { mutableStateOf(splitTunnelManager.getSelectedPackages()) }
    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = SplitTunnelManager.loadInstalledApps(context.packageManager)
            withContext(Dispatchers.Main) {
                appsList = loaded
                isLoading = false
            }
        }
    }

    val filteredApps = remember(appsList, searchQuery) {
        if (searchQuery.isBlank()) {
            appsList
        } else {
            val q = searchQuery.lowercase()
            appsList.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Исключения приложений", color = TextPrimary, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Master toggle card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Раздельное туннелирование", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                if (isEnabled) "Правила активны" else "Весь трафик направляется в VPN",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = {
                                isEnabled = it
                                splitTunnelManager.isEnabled = it
                                onChanged()
                            }
                        )
                    }

                    if (isEnabled) {
                        Divider(color = SurfaceVariantDark)
                        Text("Режим работы", color = TextSecondary, fontSize = 12.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = mode == SplitTunnelMode.DISALLOW_SELECTED,
                                onClick = {
                                    mode = SplitTunnelMode.DISALLOW_SELECTED
                                    splitTunnelManager.mode = mode
                                    onChanged()
                                },
                                label = { Text("Исключить выбранные", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = mode == SplitTunnelMode.ALLOW_SELECTED,
                                onClick = {
                                    mode = SplitTunnelMode.ALLOW_SELECTED
                                    splitTunnelManager.mode = mode
                                    onChanged()
                                },
                                label = { Text("Только выбранные", fontSize = 11.sp) }
                            )
                        }

                        Button(
                            onClick = {
                                val current = selectedPackages.toMutableSet()
                                current.addAll(SplitTunnelManager.POPULAR_RU_APPS)
                                selectedPackages = current
                                splitTunnelManager.setSelectedPackages(current)
                                onChanged()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Выбрать сервисы РФ (Банки, Госуслуги, VK)", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск по названию или пакету…", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = SurfaceVariantDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryBlue)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSelected = selectedPackages.contains(app.packageName)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    splitTunnelManager.togglePackage(app.packageName)
                                    selectedPackages = splitTunnelManager.getSelectedPackages()
                                    onChanged()
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    app.icon?.let { icon ->
                                        Image(
                                            bitmap = icon.toBitmap(48, 48).asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = app.label,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = app.packageName,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        splitTunnelManager.togglePackage(app.packageName)
                                        selectedPackages = splitTunnelManager.getSelectedPackages()
                                        onChanged()
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = PrimaryBlue)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
