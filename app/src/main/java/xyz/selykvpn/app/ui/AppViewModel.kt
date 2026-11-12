package xyz.selykvpn.app.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import xyz.selykvpn.app.BuildConfig
import xyz.selykvpn.app.SelykApp
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.AccountStatus
import xyz.selykvpn.app.data.AppJson
import xyz.selykvpn.app.data.ExclusionSettings
import xyz.selykvpn.app.data.SettingsFileException
import xyz.selykvpn.app.data.SettingsImport
import xyz.selykvpn.app.data.SettingsTransfer
import xyz.selykvpn.app.data.TransferKind
import xyz.selykvpn.app.data.RouteAction
import xyz.selykvpn.app.data.RoutingSettings
import xyz.selykvpn.app.data.ShareSettings
import xyz.selykvpn.app.data.TariffAccess
import xyz.selykvpn.app.data.UPDATE_FROM_SUBSCRIPTION
import xyz.selykvpn.app.data.WhitelistEvent
import xyz.selykvpn.app.data.KeyParser
import xyz.selykvpn.app.data.ServerEntry
import xyz.selykvpn.app.data.ServerKind
import xyz.selykvpn.app.data.SubscriptionError
import xyz.selykvpn.app.data.TvPairing
import xyz.selykvpn.app.data.UpdateCheckOutcome
import xyz.selykvpn.app.data.UpdateInfo
import xyz.selykvpn.app.data.await
import xyz.selykvpn.app.data.evaluateUpdateCheck
import xyz.selykvpn.app.data.exclusionSettings
import xyz.selykvpn.app.update.UpdateProgress
import xyz.selykvpn.app.update.Updater
import xyz.selykvpn.app.vpn.ConnectionLog
import xyz.selykvpn.app.vpn.VpnController
import xyz.selykvpn.app.vpn.Xray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Ping {
    data object Testing : Ping
    data class Ok(val ms: Long) : Ping
    data object Failed : Ping
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class ConfirmReplace(val key: KeyParser.Key) : ImportState
}

data class UiMessage(@param:StringRes val text: Int, val args: List<Any> = emptyList())

/** Servers and auto-selects get a ping (auto-select: through its own routing, like the VPN). */
private val ServerEntry.pingable: Boolean get() = kind == ServerKind.SERVER || kind == ServerKind.AUTO

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = (app as SelykApp).graph

    val subscription = graph.subscriptions.state
    val refreshing = graph.subscriptions.refreshing
    val account = graph.account.state
    val config = graph.remoteConfig.state
    val vpnState = VpnController.state
    val traffic = VpnController.traffic
    val freeAccessStage = VpnController.freeAccessStage
    val captcha = VpnController.captcha

    val exclusions: StateFlow<ExclusionSettings> = exclusionSettings(graph.settings, graph.remoteConfig)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExclusionSettings(emptySet(), emptySet(), true, emptySet()))

    val shareEnabled: StateFlow<Boolean> =
        graph.settings.shareEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val share = VpnController.share

    val shareSettings: StateFlow<ShareSettings> = graph.settings.share.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        ShareSettings(false, ShareSettings.DEFAULT_HTTP_PORT, ShareSettings.DEFAULT_SOCKS_PORT, false, "", ""),
    )
    val routing: StateFlow<RoutingSettings> = graph.settings.routing.stateIn(viewModelScope, SharingStarted.Eagerly, RoutingSettings())
    val subscriptionUpdateHours: StateFlow<Int> =
        graph.settings.subscriptionUpdateHours.stateIn(viewModelScope, SharingStarted.Eagerly, UPDATE_FROM_SUBSCRIPTION)

    val whitelistDetect: StateFlow<Boolean> =
        graph.settings.whitelistDetect.stateIn(viewModelScope, SharingStarted.Eagerly, graph.settings.whitelistDetectDefault)
    val whitelistStatus = graph.whitelist.status
    val whitelistChecking = graph.whitelist.checking
    val whitelistLastCheck = graph.whitelist.lastCheck

    val connectionLog: StateFlow<Boolean> = graph.settings.connectionLog.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val activeMode: StateFlow<String> = graph.settings.activeMode.stateIn(viewModelScope, SharingStarted.Eagerly, "regular")
    val themePalette: StateFlow<String> = graph.settings.themePalette.stateIn(viewModelScope, SharingStarted.Eagerly, "selyk")
    val freeAccessConfig: StateFlow<xyz.selykvpn.app.model.FreeAccessConfig> = graph.settings.freeAccessConfig
        .map { str ->
            if (str.isNullOrBlank()) xyz.selykvpn.app.model.FreeAccessConfig()
            else runCatching { AppJson.decodeFromString<xyz.selykvpn.app.model.FreeAccessConfig>(str) }
                .getOrDefault(xyz.selykvpn.app.model.FreeAccessConfig())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, xyz.selykvpn.app.model.FreeAccessConfig())

    val freeAccessAllowed: StateFlow<Boolean> = combine(subscription, account, freeAccessConfig) { sub, acc, cfg ->
        TariffAccess.isFreeAccessAllowed(
            hasSubscription = sub != null,
            account = acc,
            freeAccessConfig = cfg,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        TariffAccess.isFreeAccessAllowed(
            hasSubscription = graph.subscriptions.state.value != null,
            account = graph.account.state.value,
            freeAccessConfig = freeAccessConfig.value,
        ),
    )

    fun notifyFreeAccessLocked() {
        _messages.tryEmit(UiMessage(R.string.free_access_tariff_locked))
    }

    fun updateFreeAccessConfig(cfg: xyz.selykvpn.app.model.FreeAccessConfig) = viewModelScope.launch {
        graph.settings.setFreeAccessConfig(AppJson.encodeToString(cfg))
        if (activeMode.value == "free_access" && vpnState.value is VpnController.State.Connected) {
            VpnController.startFreeAccess(
                app,
                cfg,
                exclusions.value.effective,
                reconnect = true,
                logEnabled = connectionLog.value,
            )
        }
    }

    private suspend fun syncFreeAccessFromStatus(status: AccountStatus?) {
        if (status != null && status.found && !status.isWhitelist && subscription.value != null) {
            val current = freeAccessConfig.value
            val cleared = current.clearGatewayCredentials()
            if (cleared != current) {
                graph.settings.setFreeAccessConfig(AppJson.encodeToString(cleared))
            }
            graph.settings.setWhitelistAutoSwitched(null)
            if (activeMode.value == "free_access") {
                graph.settings.setActiveMode("regular")
            }
            if (VpnController.activeBackend == VpnController.Backend.FREE_ACCESS && VpnController.isActive) {
                VpnController.switchMode(app, "regular")
            }
            return
        }
        val uri = status?.vkturnUri?.takeIf { it.isNotBlank() }
        val parsedRemote = uri?.let { xyz.selykvpn.app.model.FreeAccessConfig.parse(it) }
        val extraPass = status?.vkturnPassword?.takeIf { it.isNotBlank() }
        val remoteCfg = when {
            parsedRemote != null && parsedRemote.serverPassword.isBlank() && extraPass != null ->
                parsedRemote.copy(serverPassword = extraPass)
            else -> parsedRemote
        } ?: return

        val current = freeAccessConfig.value
        val merged = current.mergeFromRemote(remoteCfg)
        if (merged != current) {
            graph.settings.setFreeAccessConfig(AppJson.encodeToString(merged))
        }
    }

    fun setMode(mode: String) = viewModelScope.launch {
        if (mode == "free_access" && !freeAccessAllowed.value) {
            _messages.emit(UiMessage(R.string.free_access_tariff_locked))
            return@launch
        }
        graph.settings.setWhitelistAutoSwitched(null)
        graph.settings.setActiveMode(mode)
        if (VpnController.isActive) {
            VpnController.switchMode(app, mode)
        }
    }

    fun setThemePalette(palette: String) = viewModelScope.launch {
        graph.settings.setThemePalette(palette)
    }

    fun setConnectionLog(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setConnectionLog(enabled)
        VpnController.setFreeAccessLogEnabled(app, enabled)
        if (!enabled) {
            ConnectionLog.clear()
            xyz.selykvpn.app.vpn.FreeAccessLog.clear(app)
        }
    }

    fun checkWhitelist() = graph.whitelist.checkNow()

    fun setWhitelistDetect(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setWhitelistDetect(enabled)
        if (!enabled) {
            graph.settings.setWhitelistAutoSwitched(null)
        } else {
            graph.whitelist.checkNow()
        }
    }

    fun setSubscriptionUpdateHours(hours: Int) = viewModelScope.launch { graph.settings.setSubscriptionUpdateHours(hours) }

    fun setSharePorts(httpPort: Int, socksPort: Int) = viewModelScope.launch { graph.settings.setSharePorts(httpPort, socksPort) }
    fun setShareAuth(enabled: Boolean) = viewModelScope.launch { graph.settings.setShareAuth(enabled) }
    fun resetShareCredentials() = viewModelScope.launch { graph.settings.resetShareCredentials() }

    fun setRoutingEnabled(enabled: Boolean) = viewModelScope.launch { graph.settings.setRoutingEnabled(enabled) }
    fun setRoutingLanDirect(enabled: Boolean) = viewModelScope.launch { graph.settings.setRoutingLanDirect(enabled) }
    fun setRoutingRuDirect(enabled: Boolean) = viewModelScope.launch { graph.settings.setRoutingRuDirect(enabled) }
    fun addRoutingRules(action: RouteAction, entries: List<String>) = viewModelScope.launch {
        graph.settings.addRoutingRules(action, entries)
    }
    fun removeRoutingRule(action: RouteAction, entry: String) = viewModelScope.launch {
        graph.settings.removeRoutingRule(action, entry)
    }
    fun clearRoutingRules(action: RouteAction) = viewModelScope.launch { graph.settings.clearRoutingRules(action) }
    fun replaceRoutingRule(action: RouteAction, old: String, entries: List<String>) = viewModelScope.launch {
        graph.settings.replaceRoutingRule(action, old, entries)
    }

    // region Settings files

    private val _pendingImport = MutableStateFlow<SettingsImport?>(null)

    /** A settings file waiting for "replace or add?". */
    val pendingImport: StateFlow<SettingsImport?> = _pendingImport.asStateFlow()

    fun exportText(kind: TransferKind): String = when (kind) {
        TransferKind.ROUTING -> SettingsTransfer.exportRouting(routing.value)
        TransferKind.APPS -> SettingsTransfer.exportApps(exclusions.value)
    }

    fun saveExport(kind: TransferKind, uri: Uri) = viewModelScope.launch {
        val text = exportText(kind)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }.isSuccess
        }
        _messages.emit(UiMessage(if (ok) R.string.export_saved else R.string.export_failed))
    }

    /** Reads a settings file picked in the app or opened from a messenger, then asks what to do with it. */
    fun openSettingsFile(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
                    input.readNBytesCompat(SettingsTransfer.MAX_BYTES + 1)
                }
                if (bytes.size > SettingsTransfer.MAX_BYTES) throw SettingsFileException()
                SettingsTransfer.parse(bytes.decodeToString())
            }
        }
        result.onSuccess { _pendingImport.value = it }.onFailure { e ->
            _messages.emit(UiMessage(if (e is SettingsFileException) R.string.import_error_format else R.string.import_error_read))
        }
    }

    fun applyImport(replace: Boolean) {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            when (pending) {
                is SettingsImport.Routing -> {
                    graph.settings.importRouting(pending.settings, replace)
                    _messages.emit(UiMessage(R.string.import_done_routing))
                }
                is SettingsImport.Apps -> {
                    graph.settings.importApps(pending.apps, replace)
                    _messages.emit(UiMessage(R.string.import_done_apps))
                }
            }
        }
    }

    fun dismissImport() {
        _pendingImport.value = null
    }

    // endregion

    val serversOnLeft: StateFlow<Boolean> =
        graph.settings.serversOnLeft.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setServersOnLeft(onLeft: Boolean) = viewModelScope.launch { graph.settings.setServersOnLeft(onLeft) }

    private val selectedKey: StateFlow<String?> =
        graph.settings.selectedServer.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedServer: StateFlow<ServerEntry?> = combine(subscription, selectedKey) { info, key ->
        info?.selectable?.let { list -> list.firstOrNull { it.key == key } ?: list.firstOrNull() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val update: StateFlow<UpdateInfo?> = combine(config, graph.settings.skippedUpdate) { cfg, skipped ->
        cfg.update?.takeIf { Updater.SUPPORTED && it.versionCode > BuildConfig.VERSION_CODE && it.versionCode > skipped }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pings = MutableStateFlow<Map<String, Ping>>(emptyMap())
    val pings: StateFlow<Map<String, Ping>> = _pings.asStateFlow()
    /** Emoji of the selected server tab, null for "All". Kept while the app is open. */
    private val _serverFilter = MutableStateFlow<String?>(null)
    val serverFilter: StateFlow<String?> = _serverFilter.asStateFlow()

    fun setServerFilter(emoji: String?) {
        _serverFilter.value = emoji
    }

    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Key problems are shown as a dialog (with a shortcut to the bot), not a snackbar. */
    private val _keyError = MutableStateFlow<Int?>(null)
    val keyError: StateFlow<Int?> = _keyError.asStateFlow()

    private val _updateProgress = MutableStateFlow<UpdateProgress?>(null)
    val updateProgress: StateFlow<UpdateProgress?> = _updateProgress.asStateFlow()
    private var updateJob: Job? = null
    private var pendingInstallInfo: UpdateInfo? = null

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { graph.remoteConfig.refresh() }
        viewModelScope.launch {
            VpnController.errors.collect { _messages.emit(UiMessage(R.string.vpn_error, listOf(it))) }
        }
        viewModelScope.launch {
            graph.whitelist.events.collect { event ->
                val text = when (event) {
                    is WhitelistEvent.Switched -> if (event.mode == "free_access") {
                        R.string.whitelist_switched_on
                    } else {
                        R.string.whitelist_switched_off
                    }
                    WhitelistEvent.SetupRequired -> R.string.whitelist_setup_required
                    WhitelistEvent.TariffLocked -> R.string.whitelist_tariff_locked
                }
                _messages.emit(UiMessage(text))
            }
        }
        onResume()
    }

    /** Called when the app comes to the foreground: refresh what is outdated and sync `:vkturn`. */
    fun onResume() {
        VpnController.syncState(app)
        val pendingUpdate = pendingInstallInfo
        if (pendingUpdate != null && Updater.canInstallPackages(app)) {
            pendingInstallInfo = null
            startUpdateDownloadOrInstall(pendingUpdate)
        }
        viewModelScope.launch { graph.remoteConfig.refreshIfOlderThan(60 * 60_000L) }
        graph.whitelist.checkIfStale()
        val info = subscription.value ?: return
        viewModelScope.launch {
            syncFreeAccessFromStatus(graph.account.refresh(info.url))
        }
        viewModelScope.launch {
            if (graph.subscriptions.isStale(graph.settings.subscriptionUpdateHoursNow())) {
                runCatching { graph.subscriptions.refresh() }
            }
        }
    }

    fun showMessage(@StringRes text: Int) {
        _messages.tryEmit(UiMessage(text))
    }

    // region Keys

    fun submitKey(text: String?) {
        if (_importState.value == ImportState.Loading) return
        // The QR code of a TV (scanned here or opened from the camera app): send it our key.
        TvPairing.codeFromLink(text)?.let { code ->
            if (subscription.value == null) _keyError.value = R.string.tv_send_need_key else _tvSend.value = code
            return
        }
        when (val result = KeyParser.parse(text, config.value.allowedSubscriptionHosts)) {
            is KeyParser.Result.FreeAccess -> {
                updateFreeAccessConfig(freeAccessConfig.value.mergeFromRemote(result.config))
                setMode("free_access")
                _messages.tryEmit(UiMessage(R.string.free_access_imported))
            }
            KeyParser.Result.Empty -> _keyError.value = R.string.error_empty_clipboard
            KeyParser.Result.NotALink -> _keyError.value = R.string.error_not_a_key
            KeyParser.Result.Foreign -> _keyError.value = R.string.error_foreign_key
            is KeyParser.Result.Ok -> {
                val current = subscription.value
                when {
                    current == null -> import(result.key)
                    !result.key.directConfigs.isNullOrEmpty() -> import(result.key)
                    current.url == result.key.url -> refreshSubscription(showMessage = true)
                    else -> _importState.value = ImportState.ConfirmReplace(result.key)
                }
            }
        }
    }

    /** Code of the TV that asked for this phone's key; the dialog confirms sending it. */
    private val _tvSend = MutableStateFlow<String?>(null)
    val tvSend: StateFlow<String?> = _tvSend.asStateFlow()

    fun cancelTvSend() {
        _tvSend.value = null
    }

    fun confirmTvSend() {
        val code = _tvSend.value ?: return
        val key = subscription.value?.url ?: return
        _tvSend.value = null
        viewModelScope.launch {
            val message = when (graph.tvPairing.send(code, key)) {
                TvPairing.SendResult.SENT -> R.string.tv_sent
                TvPairing.SendResult.NO_CODE -> R.string.tv_send_no_code
                TvPairing.SendResult.FOREIGN_KEY -> R.string.error_foreign_key
                TvPairing.SendResult.TOO_MANY -> R.string.tv_send_too_many
                TvPairing.SendResult.FAILED -> R.string.tv_send_failed
            }
            _messages.emit(UiMessage(message))
        }
    }

    fun confirmReplace() {
        val state = _importState.value as? ImportState.ConfirmReplace ?: return
        import(state.key)
    }

    fun cancelImport() {
        if (_importState.value is ImportState.ConfirmReplace) _importState.value = ImportState.Idle
    }

    fun dismissKeyError() {
        _keyError.value = null
    }

    private fun import(key: KeyParser.Key) = viewModelScope.launch {
        _importState.value = ImportState.Loading
        try {
            val prevUrl = subscription.value?.url
            val wasConnected = VpnController.isActive
            val wasFreeAccess = VpnController.activeBackend == VpnController.Backend.FREE_ACCESS
            graph.subscriptions.import(key)
            _pings.value = emptyMap()
            graph.account.clear()
            if (prevUrl != null && prevUrl != key.url) {
                val cleared = freeAccessConfig.value.clearGatewayCredentials()
                if (cleared != freeAccessConfig.value) {
                    graph.settings.setFreeAccessConfig(AppJson.encodeToString(cleared))
                }
                graph.settings.setWhitelistAutoSwitched(null)
            }
            syncFreeAccessFromStatus(graph.account.refresh(key.url, force = true))
            _messages.emit(UiMessage(R.string.key_added))
            if (wasConnected && (!wasFreeAccess || freeAccessAllowed.value)) {
                VpnController.reconnect(app)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AppViewModel", "Import failed with exception", e)
            _keyError.value = e.messageRes()
        } finally {
            _importState.value = ImportState.Idle
        }
    }

    fun refreshSubscription(showMessage: Boolean = true) = viewModelScope.launch {
        try {
            graph.subscriptions.refresh()
            subscription.value?.let {
                launch { syncFreeAccessFromStatus(graph.account.refresh(it.url, force = true)) }
            }
            if (showMessage) _messages.emit(UiMessage(R.string.servers_updated))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(UiMessage(e.messageRes()))
        }
    }

    fun logout() = viewModelScope.launch {
        VpnController.stop(app)
        graph.subscriptions.clear()
        graph.account.clear()
        _pings.value = emptyMap()
    }

    // endregion

    // region Servers

    fun selectServer(key: String) = viewModelScope.launch {
        graph.settings.setSelectedServer(key)
        VpnController.reconnect(app)
    }

    private var pingAll: Job? = null
    private var pingRun = 0
    /** Shared by restarted runs: a cancelled run's measurements keep their slot until they finish. */
    private val pingLimit = Semaphore(6)
    /** Servers whose ping is being measured right now (main thread). Queued ones aren't here. */
    private val measuring = mutableSetOf<String>()

    /** Pressed again while pinging: starts over, like Happ. */
    fun testPings() {
        val info = subscription.value ?: return
        pingAll?.cancel()
        val run = ++pingRun
        pingAll = viewModelScope.launch {
            _pinging.value = true
            try {
                val servers = info.servers.filter { it.pingable }
                _pings.update { old -> old + servers.associate { it.key to Ping.Testing } }
                servers.map { entry ->
                    async {
                        pingLimit.withPermit {
                            // Already pinged by a long press, or still being measured (it will report).
                            if (_pings.value[entry.key] == Ping.Testing && entry.key !in measuring) measure(entry)
                        }
                    }
                }.awaitAll()
            } finally {
                if (run == pingRun) _pinging.value = false
            }
        }
    }

    /** Long press on a server or auto-select: ping just this one, also when it waits in the "ping all" queue. */
    fun pingServer(entry: ServerEntry) {
        if (!entry.pingable || entry.key in measuring) return
        viewModelScope.launch {
            _pings.update { it + (entry.key to Ping.Testing) }
            measure(entry)
        }
    }

    private suspend fun measure(entry: ServerEntry) {
        measuring += entry.key
        try {
            // The core can't be interrupted; a started measurement reports even if its run was restarted.
            val ms = withContext(NonCancellable) {
                graph.subscriptions.configFor(entry.key)?.let { Xray.measureDelay(app, it, config.value.delayTestUrl) }
            }
            _pings.update { it + (entry.key to (ms?.let(Ping::Ok) ?: Ping.Failed)) }
        } finally {
            measuring -= entry.key
        }
    }

    // endregion

    // region VPN

    fun connect() {
        if (activeMode.value == "free_access") {
            if (!freeAccessAllowed.value) {
                _messages.tryEmit(UiMessage(R.string.free_access_tariff_locked))
                return
            }
            val cfg = freeAccessConfig.value
            if (!cfg.hasRealVKCallLink()) {
                _messages.tryEmit(UiMessage(R.string.free_access_need_call_link))
                return
            }
            VpnController.startFreeAccess(app, cfg, exclusions.value.effective, logEnabled = connectionLog.value)
            return
        }
        if (subscription.value == null) return
        VpnController.startRegular(app)
    }

    fun disconnect() = VpnController.stop(app)

    // The VPN service notices these changes itself and rebuilds the tunnel.
    fun setAppExcluded(pkg: String, excluded: Boolean) = viewModelScope.launch {
        graph.settings.setAppExcluded(pkg, excluded, exclusions.value.recommended)
    }

    fun setUseRecommendedExclusions(enabled: Boolean) = viewModelScope.launch {
        graph.settings.setUseRecommendedExclusions(enabled)
    }

    fun setShareEnabled(enabled: Boolean) = viewModelScope.launch { graph.settings.setShareEnabled(enabled) }

    // endregion

    // region Updates & Free Access Captcha

    fun submitCaptcha(answer: String) = VpnController.submitCaptcha(app, answer)

    fun refreshCaptcha() = VpnController.refreshCaptcha(app)

    fun cancelCaptcha() = VpnController.cancelCaptcha(app)

    suspend fun loadCaptchaBitmap(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            graph.http.withClient { client ->
                client.newCall(okhttp3.Request.Builder().url(url).build()).await().use { resp ->
                    if (!resp.isSuccessful) return@withClient null
                    val bytes = resp.body.bytes()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        }.getOrNull()
    }

    fun installUpdate() {
        val info = update.value ?: return
        if (_updateProgress.value != null) return
        if (!Updater.canInstallPackages(app)) {
            pendingInstallInfo = info
            _messages.tryEmit(UiMessage(R.string.update_permission_needed))
            Updater.openInstallPermissionSettings(app)
            return
        }
        startUpdateDownloadOrInstall(info)
    }

    private fun startUpdateDownloadOrInstall(info: UpdateInfo) {
        if (_updateProgress.value != null) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _updateProgress.value = UpdateProgress()
            try {
                val file = Updater.download(app, graph.http, info) { _updateProgress.value = it }
                if (Updater.canInstallPackages(app)) {
                    pendingInstallInfo = null
                    Updater.install(app, file)
                } else {
                    pendingInstallInfo = info
                    _messages.emit(UiMessage(R.string.update_permission_needed))
                    Updater.openInstallPermissionSettings(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.emit(UiMessage(R.string.update_failed))
            } finally {
                _updateProgress.value = null
            }
        }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        updateJob = null
        pendingInstallInfo = null
        _updateProgress.value = null
    }

    /** Hides the update card until a newer version is published. */
    fun skipUpdate() {
        val info = update.value ?: return
        viewModelScope.launch { graph.settings.setSkippedUpdate(info.versionCode) }
    }

    /** A manual check brings back a skipped update and reports the outcome. */
    fun checkForUpdates() = viewModelScope.launch {
        val refreshOk = graph.remoteConfig.refresh()
        val available = config.value.update?.takeIf { Updater.SUPPORTED && it.versionCode > BuildConfig.VERSION_CODE }
        when (evaluateUpdateCheck(refreshOk, available != null)) {
            UpdateCheckOutcome.AVAILABLE -> {
                graph.settings.setSkippedUpdate(0)
                _messages.emit(UiMessage(R.string.settings_update_available, listOf(available!!.versionName)))
            }
            UpdateCheckOutcome.FAILED -> _messages.emit(UiMessage(R.string.settings_update_check_failed))
            UpdateCheckOutcome.UP_TO_DATE -> _messages.emit(UiMessage(R.string.settings_up_to_date))
        }
    }

    // endregion
}

/** Reads at most [limit] bytes (InputStream.readNBytes needs Android 13). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@StringRes
fun Throwable.messageRes(): Int = when (this) {
    is SubscriptionError.Foreign -> R.string.error_foreign_key
    is SubscriptionError.NotFound -> R.string.error_not_found
    is SubscriptionError.BadFormat -> R.string.error_bad_format
    is SubscriptionError.NoServers -> R.string.error_no_servers
    is SubscriptionError.DeviceLimit -> R.string.error_device_limit
    is SubscriptionError.Expired -> R.string.error_subscription_expired
    is SubscriptionError.Unsupported -> R.string.error_subscription_unsupported
    else -> R.string.error_download
}
