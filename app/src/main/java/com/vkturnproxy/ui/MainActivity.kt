package com.vkturnproxy.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.vkturnproxy.BuildConfig
import com.vkturnproxy.VkTurnApp
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.parser.ConfigParser
import com.vkturnproxy.service.VkTurnVpnService
import com.vkturnproxy.service.VpnStatus
import com.vkturnproxy.ui.screens.*
import com.vkturnproxy.ui.theme.BgDark
import com.vkturnproxy.ui.theme.PrimaryBlue
import com.vkturnproxy.ui.theme.SurfaceDark
import com.vkturnproxy.ui.theme.TextSecondary
import com.vkturnproxy.ui.theme.VkTurnTheme

enum class Screen(val title: String, val icon: ImageVector) {
    DASHBOARD("Главная", Icons.Default.FlashOn),
    SERVERS("Серверы", Icons.Default.Dns),
    SPEEDTEST("Скорость", Icons.Default.Speed),
    SPLIT_TUNNEL("Исключения", Icons.Default.Apps),
    HOTSPOT("Раздача", Icons.Default.WifiTethering)
}

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectActiveServer()
        } else {
            Toast.makeText(this, "Разрешение на VPN отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            VkTurnTheme {
                MainContent()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        val parsed = ConfigParser.parse(data)
        if (parsed != null) {
            VkTurnApp.instance.serverRepository.saveServer(parsed)
            Toast.makeText(this, "Конфигурация «${parsed.name}» импортирована!", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleVpn() {
        val currentStatus = VkTurnVpnService.statusFlow.value
        if (currentStatus == VpnStatus.CONNECTED || currentStatus == VpnStatus.CONNECTING || currentStatus == VpnStatus.WAITING_TURN) {
            VkTurnVpnService.stop(this)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                connectActiveServer()
            }
        }
    }

    private fun connectActiveServer() {
        val active = VkTurnApp.instance.serverRepository.activeServerFlow.value
        if (active != null) {
            VkTurnVpnService.start(this, active)
        } else {
            Toast.makeText(this, "Сначала выберите или добавьте сервер", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun MainContent() {
        var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
        var editingServer by remember { mutableStateOf<ServerConfig?>(null) }

        val serverRepo = VkTurnApp.instance.serverRepository
        val splitTunnelMgr = VkTurnApp.instance.splitTunnelManager
        val updateMgr = VkTurnApp.instance.updateManager

        val servers by serverRepo.serversFlow.collectAsState()
        val activeServer by serverRepo.activeServerFlow.collectAsState()
        val vpnStatus by VkTurnVpnService.statusFlow.collectAsState()
        val vpnStats by VkTurnVpnService.statsFlow.collectAsState()
        val updateState by updateMgr.updateState.collectAsState()

        val prefs = remember { getSharedPreferences("vkturn_settings", Context.MODE_PRIVATE) }
        var isRootHotspotEnabled by remember { mutableStateOf(prefs.getBoolean("root_hotspot_enabled", false)) }
        var splitTunnelAppCount by remember { mutableStateOf(splitTunnelMgr.getSelectedPackages().size) }

        // Silent background update check on app launch
        LaunchedEffect(Unit) {
            updateMgr.checkForUpdates(BuildConfig.VERSION_NAME, silent = true)
        }

        Scaffold(
            bottomBar = {
                if (editingServer == null) {
                    NavigationBar(
                        containerColor = SurfaceDark
                    ) {
                        Screen.values().forEach { screen ->
                            NavigationBarItem(
                                selected = currentScreen == screen,
                                onClick = { currentScreen = screen },
                                icon = { Icon(screen.icon, contentDescription = screen.title) },
                                label = { Text(screen.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PrimaryBlue,
                                    selectedTextColor = PrimaryBlue,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                    indicatorColor = SurfaceDark
                                )
                            )
                        }
                    }
                }
            },
            containerColor = BgDark
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = BgDark
            ) {
                if (editingServer != null) {
                    ServerEditScreen(
                        server = editingServer!!,
                        onSave = { updated ->
                            serverRepo.saveServer(updated)
                            editingServer = null
                        },
                        onBack = { editingServer = null }
                    )
                } else {
                    when (currentScreen) {
                        Screen.DASHBOARD -> DashboardScreen(
                            status = vpnStatus,
                            stats = vpnStats,
                            activeServer = activeServer,
                            isRootHotspotEnabled = isRootHotspotEnabled,
                            splitTunnelAppCount = splitTunnelAppCount,
                            updateState = updateState,
                            onToggleVpn = { toggleVpn() },
                            onSelectServerClicked = { currentScreen = Screen.SERVERS },
                            onHotspotClicked = { currentScreen = Screen.HOTSPOT },
                            onSplitTunnelClicked = { currentScreen = Screen.SPLIT_TUNNEL },
                            onSpeedTestClicked = { currentScreen = Screen.SPEEDTEST },
                            onCallCreated = { newLink ->
                                val active = activeServer
                                if (active != null) {
                                    val currentLinks = active.vkLink
                                    val updatedVkLink = if (currentLinks.isBlank()) newLink else "$currentLinks\n$newLink"
                                    val updated = active.copy(vkLink = updatedVkLink)
                                    serverRepo.saveServer(updated)
                                    Toast.makeText(this@MainActivity, "Звонок VK создан и привязан к «${updated.name}»!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Звонок VK создан и скопирован в буфер!", Toast.LENGTH_LONG).show()
                                }
                            },
                            onCheckUpdateClicked = {
                                updateMgr.checkForUpdates(BuildConfig.VERSION_NAME, silent = false)
                            },
                            onDownloadUpdateClicked = { info ->
                                updateMgr.downloadAndInstall(info)
                            },
                            onInstallApkClicked = { file ->
                                updateMgr.installApk(file)
                            },
                            onDismissUpdateClicked = {
                                updateMgr.dismiss()
                            }
                        )

                        Screen.SERVERS -> ServersScreen(
                            servers = servers,
                            activeServerId = activeServer?.id,
                            onSelectServer = { serverRepo.setActiveServer(it) },
                            onEditServer = { editingServer = it },
                            onDeleteServer = { serverRepo.deleteServer(it) },
                            onAddServer = { editingServer = it }
                        )

                        Screen.SPEEDTEST -> SpeedTestScreen()

                        Screen.SPLIT_TUNNEL -> SplitTunnelScreen(
                            splitTunnelManager = splitTunnelMgr,
                            onChanged = {
                                splitTunnelAppCount = splitTunnelMgr.getSelectedPackages().size
                            }
                        )

                        Screen.HOTSPOT -> HotspotScreen(
                            onChanged = {
                                isRootHotspotEnabled = prefs.getBoolean("root_hotspot_enabled", false)
                            }
                        )
                    }
                }
            }
        }
    }
}
