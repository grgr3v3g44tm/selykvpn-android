package xyz.selykvpn.app.update

import android.content.Context
import xyz.selykvpn.app.data.Http
import xyz.selykvpn.app.data.UpdateInfo
import java.io.File

/** Google Play build: updates are delivered by the store only. */
object Updater {
    const val SUPPORTED = false

    fun canInstallPackages(context: Context): Boolean = false

    fun openInstallPermissionSettings(context: Context) = Unit

    fun cachedApk(context: Context, info: UpdateInfo): File? = null

    suspend fun download(
        context: Context,
        http: Http,
        info: UpdateInfo,
        onProgress: (UpdateProgress) -> Unit,
    ): File = throw UnsupportedOperationException("updates are delivered by Google Play")

    fun install(context: Context, file: File) = Unit
}
