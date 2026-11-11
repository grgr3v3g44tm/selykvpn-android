package xyz.selykvpn.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.centeredMaxWidth
import xyz.selykvpn.app.ui.common.openUrl
import xyz.selykvpn.app.ui.common.readClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: AppViewModel, onScan: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    var showManual by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .centeredMaxWidth(480.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { context.openUrl(config.telegramBotUrl) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_telegram), null, Modifier.size(22.dp))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_telegram), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = stringResource(R.string.onboarding_telegram_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                FilledTonalButton(
                    onClick = { viewModel.submitKey(context.readClipboard()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_paste))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_scan))
                }
                TextButton(onClick = { showManual = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.onboarding_manual))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(32.dp))
            Image(
                painter = painterResource(R.drawable.ic_selyk_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Spacer(Modifier.height(36.dp))
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showManual) {
        ManualKeyDialog(
            onDismiss = { showManual = false },
            onSubmit = { text ->
                showManual = false
                viewModel.submitKey(text)
            },
        )
    }
}

@Composable
internal fun ManualKeyDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.manual_label)) },
                placeholder = { Text(stringResource(R.string.manual_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
