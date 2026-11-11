package xyz.selykvpn.app.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.TransferKind
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.AppIcon
import xyz.selykvpn.app.ui.common.centeredMaxWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [system]: no launcher icon (Android Auto, Google Play services, system components). */
private data class AppInfo(val packageName: String, val label: String, val system: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    val apps by produceState<List<AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val withIcon = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                // Overlays and other packages without code never connect anywhere.
                .filter { it.packageName != context.packageName && (it.flags and ApplicationInfo.FLAG_HAS_CODE) != 0 }
                .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString(), system = it.packageName !in withIcon) }
                .sortedBy { it.label.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = { TransferMenu(TransferKind.APPS, viewModel) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(720.dp),
        ) {
            Text(
                stringResource(R.string.split_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.split_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            FilterChip(
                selected = showSystem,
                onClick = { showSystem = !showSystem },
                label = { Text(stringResource(R.string.split_show_system)) },
                leadingIcon = if (showSystem) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val excluded = exclusions.effective
                val filtered = remember(list, query, excluded, showSystem) {
                    // Excluded system apps stay in sight, so the recommended ones can be turned off.
                    list.filter { showSystem || !it.system || it.packageName in excluded }
                        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                        .sortedByDescending { it.packageName in excluded }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in excluded
                        val toggle = { viewModel.setAppExcluded(app.packageName, !checked) }
                        val recommended = exclusions.isRecommended(app.packageName)
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = {
                                Text(
                                    text = if (recommended) stringResource(R.string.split_recommended) else app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = { AppIcon(app.packageName) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
                            modifier = Modifier.clickable { toggle() },
                        )
                    }
                }
            }
        }
    }
}

