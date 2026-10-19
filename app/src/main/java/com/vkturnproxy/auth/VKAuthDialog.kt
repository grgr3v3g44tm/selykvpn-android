package com.vkturnproxy.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Lightweight bridge that launches [VKAuthActivity] in dedicated window
 * to perform reliable VK OAuth and cookie capturing without Compose WebView bugs.
 */
@Composable
fun VKAuthDialog(
    onDismiss: () -> Unit,
    onSuccess: (cookieHeader: String) -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val header = result.data?.getStringExtra(VKAuthActivity.EXTRA_COOKIE_HEADER)
                ?: VKCookieStore.getValidCookieHeader()
                ?: ""
            if (header.isNotEmpty()) {
                onSuccess(header)
            }
        }
        onDismiss()
    }

    LaunchedEffect(Unit) {
        launcher.launch(VKAuthActivity.createLoginIntent(context))
    }
}
