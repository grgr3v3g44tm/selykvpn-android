package xyz.selykvpn.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import xyz.selykvpn.app.R
import java.io.File

/** Receives session commit results from Android's [PackageInstaller]. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent != null) {
                    runCatching {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to launch install confirmation activity", e)
                        _installErrors.tryEmit(e.message ?: context.getString(R.string.update_failed))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "PackageInstaller succeeded")
                apkPath?.let { path -> runCatching { File(path).delete() } }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "PackageInstaller aborted by user")
            }
            else -> {
                Log.e(TAG, "PackageInstaller failed status=$status msg=$message")
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
                    apkPath?.let { path -> runCatching { File(path).delete() } }
                }
                val errText = context.getString(R.string.update_failed)
                _installErrors.tryEmit(errText)
                runCatching { Toast.makeText(context, errText, Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_STATUS = "xyz.selykvpn.app.ACTION_INSTALL_STATUS"
        const val EXTRA_APK_PATH = "extra_apk_path"

        private val _installErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val installErrors = _installErrors.asSharedFlow()
    }
}
