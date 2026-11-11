package xyz.selykvpn.app.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.TvPairing
import xyz.selykvpn.app.data.TvSession
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.QrCode
import xyz.selykvpn.app.ui.onboarding.ManualKeyDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface Pairing {
    data object Loading : Pairing
    data class Waiting(val session: TvSession) : Pairing
    data object Received : Pairing
    data object Offline : Pairing
}

/**
 * Adding a key on a TV: typing a link with a remote is painful, so the TV shows a QR code of
 * tgapp.selykvpn.xyz/tv/<code>. The phone sends its key there (the Селюк VPN app does it in one tap, the web
 * page takes a pasted key) and the TV picks it up from the API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvOnboardingScreen(viewModel: AppViewModel, onBack: (() -> Unit)?) {
    var state by remember { mutableStateOf<Pairing>(Pairing.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    val manualFocus = remember { FocusRequester() }
    val submit by rememberUpdatedState<(String) -> Unit>(viewModel::submitKey)

    LaunchedEffect(attempt) {
        val pairing = SelykApp.graph.tvPairing
        while (true) {
            state = Pairing.Loading
            val session = try {
                pairing.start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = Pairing.Offline
                delay(10_000)
                continue
            }
            state = Pairing.Waiting(session)
            val key = waitForKey(pairing, session) ?: continue // the code ran out: show a new one
            state = Pairing.Received
            submit(key)
            // If the key turns out to be wrong, the error dialog shows and a new code follows.
            delay(5_000)
        }
    }
    LaunchedEffect(Unit) { runCatching { manualFocus.requestFocus() } }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 56.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_selyk_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.tv_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Step(1, stringResource(R.string.tv_step_scan))
                Step(2, stringResource(R.string.tv_step_send))
                val code = (state as? Pairing.Waiting)?.session?.code
                if (code != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tv_or_code),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        TvPairing.display(code),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { showManual = true }, modifier = Modifier.focusRequester(manualFocus)) {
                    Icon(Icons.Rounded.Keyboard, null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_manual))
                }
                Text(
                    stringResource(R.string.tv_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                when (val s = state) {
                    Pairing.Loading -> CircularProgressIndicator()
                    is Pairing.Waiting -> QrCode(s.session.url, Modifier.fillMaxSize())
                    Pairing.Received -> Status(Icons.Rounded.CheckCircle, stringResource(R.string.tv_received))
                    Pairing.Offline -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Status(Icons.Rounded.CloudOff, stringResource(R.string.tv_offline))
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = { attempt++ }) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
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

/** The key once a phone has sent it; null when the code ran out. Network hiccups keep the code. */
private suspend fun waitForKey(pairing: TvPairing, session: TvSession): String? {
    val until = System.currentTimeMillis() + session.expiresIn * 1000L
    while (System.currentTimeMillis() < until) {
        try {
            pairing.awaitKey(session)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: TvPairing.Expired) {
            return null
        } catch (_: Exception) {
            delay(3_000)
        }
    }
    return null
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Status(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
