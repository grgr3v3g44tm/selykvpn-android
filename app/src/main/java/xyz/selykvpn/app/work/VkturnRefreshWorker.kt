package xyz.selykvpn.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.encodeToString
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Фоновое обновление VK TURN URI каждые 12 часов через WorkManager.
 *
 * Логика:
 * 1. Запрашивает [AccountRepository.refresh] с текущим subscriptionUrl.
 * 2. Если в ответе есть vkturnUri — мерджит в сохранённый FreeAccessConfig.
 * 3. Если VPN сейчас активен в режиме free_access — переподключает с новыми параметрами.
 * 4. Пропускает выполнение если VPN активен и URI не изменился.
 */
class VkturnRefreshWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = SelykApp.instance
            val graph = app.graph

            val subscriptionUrl = graph.subscriptions.state.value?.url
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (subscriptionUrl == null) {
                Log.d(TAG, "No subscription URL — skip VK TURN refresh")
                return@withContext Result.success()
            }

            Log.d(TAG, "Starting VK TURN URI refresh...")
            val status = graph.account.refresh(subscriptionUrl, force = true)
            val newUri = status?.vkturnUri?.takeIf { it.isNotBlank() } ?: run {
                Log.d(TAG, "No vkturnUri in status — nothing to update")
                return@withContext Result.success()
            }

            val parsedRemote = FreeAccessConfig.parse(newUri) ?: run {
                Log.w(TAG, "Failed to parse remote vkturnUri: $newUri")
                return@withContext Result.success()
            }

            val extraPass = status.vkturnPassword?.takeIf { it.isNotBlank() }
            val remoteCfg = if (parsedRemote.serverPassword.isBlank() && extraPass != null) {
                parsedRemote.copy(serverPassword = extraPass)
            } else {
                parsedRemote
            }

            val current = graph.settings.freeAccessConfigNow()
            val merged = current.mergeFromRemote(remoteCfg)
            if (merged == current) {
                Log.d(TAG, "VK TURN config unchanged — no update needed")
                return@withContext Result.success()
            }

            // Сохраняем обновлённый конфиг
            graph.settings.setFreeAccessConfig(AppJson.encodeToString(merged))
            Log.i(TAG, "VK TURN config updated successfully")

            // Если VPN активен в режиме free_access — переподключаем с новыми параметрами
            if (VpnController.activeBackend == VpnController.Backend.FREE_ACCESS && VpnController.isActive) {
                Log.d(TAG, "VPN is active in free_access mode — reconnecting with updated config")
                VpnController.startFreeAccess(
                    context = context,
                    config = merged,
                    excludedApps = graph.settings.excludedAppsNow(),
                    reconnect = true,
                    logEnabled = graph.settings.connectionLogNow(),
                )
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "VK TURN refresh failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "VkturnRefreshWorker"
        const val WORK_NAME = "vkturn_periodic_refresh"
    }
}
