package xyz.selykvpn.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.TransferKind
import xyz.selykvpn.app.ui.AppViewModel
import java.io.File

/** ⋮ in the top bar: send the settings as a file, save them, or load a file someone sent. */
@Composable
fun TransferMenu(kind: TransferKind, viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.saveExport(kind, uri)
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openSettingsFile(uri)
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.transfer_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                onClick = {
                    open = false
                    scope.launch { context.shareSettings(kind) { viewModel.exportText(kind) } }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_save)) },
                leadingIcon = { Icon(Icons.Rounded.Save, null) },
                onClick = {
                    open = false
                    runCatching { save.launch(kind.fileName) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_load)) },
                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                onClick = {
                    open = false
                    // Messengers and file managers label JSON files differently.
                    runCatching { load.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                },
            )
        }
    }
}

/** Opens the share sheet with the settings as a .json file (Telegram, e-mail, Drive...). */
private suspend fun Context.shareSettings(kind: TransferKind, buildText: () -> String) {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val text = buildText()
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, kind.fileName).apply { writeText(text) }
            FileProvider.getUriForFile(this@shareSettings, "$packageName.exports", file)
        }.getOrNull()
    } ?: return
    runCatching {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.transfer_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
