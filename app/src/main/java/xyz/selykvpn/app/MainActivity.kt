package xyz.selykvpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.ui.AppRoot
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.theme.SelykVpnTheme
import xyz.selykvpn.app.ui.theme.TvAdaptations
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) connectNow() else viewModel.showMessage(R.string.vpn_permission_denied)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val palette by viewModel.themePalette.collectAsStateWithLifecycle()
            SelykVpnTheme(palette = palette) {
                TvAdaptations {
                    AppRoot(viewModel = viewModel, onConnect = ::requestConnect)
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val file = settingsFile(intent)
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString.takeIf { file == null }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).takeIf { file == null }
            else -> null
        }
        if (file != null) viewModel.openSettingsFile(file)
        if (text != null) viewModel.submitKey(text)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) requestConnect()
        // Consume the intent so a configuration change does not import the key again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** A settings file opened from another app (content:// or file://), not a key link. */
    private fun settingsFile(intent: Intent): Uri? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        return uri?.takeIf { it.scheme == "content" || it.scheme == "file" }
    }

    /** Asks for the VPN permission first if needed, then connects. */
    fun requestConnect() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            runCatching { vpnPermission.launch(prepare) }
                .onFailure { viewModel.showMessage(R.string.vpn_permission_denied) }
        } else {
            connectNow()
        }
    }

    private fun connectNow() {
        viewModel.connect()
        askNotificationsOnce()
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            val settings = SelykApp.graph.settings
            if (!settings.notificationsAsked()) {
                settings.setNotificationsAsked()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_CONNECT = "connect"
    }
}
