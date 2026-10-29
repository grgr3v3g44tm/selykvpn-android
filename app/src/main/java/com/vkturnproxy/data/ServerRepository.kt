package com.vkturnproxy.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vkturnproxy.log.AppLog
import com.vkturnproxy.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerRepository(private val context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)
    private val gson = Gson()

    private val _serversFlow = MutableStateFlow<List<ServerConfig>>(emptyList())
    val serversFlow = _serversFlow.asStateFlow()

    private val _activeServerFlow = MutableStateFlow<ServerConfig?>(null)
    val activeServerFlow = _activeServerFlow.asStateFlow()

    init {
        migrateLegacyPrefs()
        loadServers()
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to create EncryptedSharedPreferences for ServerRepository, falling back to standard prefs", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacyPrefs() {
        try {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyJson = legacy.getString("servers_json", null)
            if (!legacyJson.isNullOrBlank() && !prefs.contains("servers_json")) {
                val activeId = legacy.getString("active_server_id", null)
                prefs.edit()
                    .putString("servers_json", legacyJson)
                    .putString("active_server_id", activeId)
                    .apply()
                legacy.edit().clear().apply()
                AppLog.i(TAG, "Migrated server configs to EncryptedSharedPreferences")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Error migrating legacy server repository", e)
        }
    }

    fun getPrivateKey(serverId: String): String {
        return prefs.getString(KEY_PREFIX_PRIVKEY + serverId, null) ?: ""
    }

    fun savePrivateKey(serverId: String, key: String) {
        prefs.edit().putString(KEY_PREFIX_PRIVKEY + serverId, key).apply()
    }

    private fun loadServers() {
        val json = prefs.getString("servers_json", null)
        val list: List<ServerConfig> = if (json != null) {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            try {
                gson.fromJson<List<ServerConfig>>(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Restore or securely persist private keys
        for (server in list) {
            val storedSecureKey = getPrivateKey(server.id)
            if (storedSecureKey.isNotBlank()) {
                server.privateKey = storedSecureKey
            } else if (server.privateKey.isNotBlank()) {
                savePrivateKey(server.id, server.privateKey)
            }
        }

        _serversFlow.value = list
        val activeId = prefs.getString("active_server_id", null)
        _activeServerFlow.value = list.find { it.id == activeId } ?: list.firstOrNull()
        saveServers(list)
    }

    fun purgeCredCache() {
        try {
            listOf(context.cacheDir, context.filesDir).forEach { dir ->
                java.io.File(dir, "creds-pool.json").delete()
            }
        } catch (_: Exception) {}
    }

    fun saveServer(config: ServerConfig, makeActive: Boolean = false) {
        val current = _serversFlow.value.toMutableList()
        // Deduplicate: match by ID, or if matching peer address & public key
        val index = current.indexOfFirst {
            it.id == config.id ||
            (config.peerPublicKey.isNotBlank() && it.peerPublicKey == config.peerPublicKey && it.peerAddress == config.peerAddress)
        }
        val targetConfig = if (index >= 0) {
            val old = current[index]
            if (old.vkLink != config.vkLink) {
                purgeCredCache()
            }
            val merged = config.copy(id = old.id)
            current[index] = merged
            merged
        } else {
            current.add(config)
            config
        }

        // Securely store WireGuard private key
        if (targetConfig.privateKey.isNotBlank()) {
            savePrivateKey(targetConfig.id, targetConfig.privateKey)
        }

        saveServers(current)
        if (makeActive || _activeServerFlow.value?.id == targetConfig.id || _activeServerFlow.value == null) {
            setActiveServer(targetConfig)
        }
    }

    fun deleteServer(id: String) {
        purgeCredCache()
        val current = _serversFlow.value.toMutableList()
        current.removeAll { it.id == id }
        prefs.edit().remove(KEY_PREFIX_PRIVKEY + id).apply()
        saveServers(current)
        if (_activeServerFlow.value?.id == id) {
            _activeServerFlow.value = current.firstOrNull()
            prefs.edit().putString("active_server_id", _activeServerFlow.value?.id).apply()
        }
    }

    fun setActiveServer(config: ServerConfig) {
        _activeServerFlow.value = config
        prefs.edit().putString("active_server_id", config.id).apply()
    }

    private fun saveServers(list: List<ServerConfig>) {
        // Ensure private keys are securely saved in keystore-backed prefs
        for (server in list) {
            if (server.privateKey.isNotBlank()) {
                savePrivateKey(server.id, server.privateKey)
            }
        }
        _serversFlow.value = list
        prefs.edit().putString("servers_json", gson.toJson(list)).apply()
    }

    companion object {
        private const val TAG = "ServerRepository"
        private const val PREFS_NAME = "vkturn_servers_secure"
        private const val LEGACY_PREFS_NAME = "vkturn_servers"
        private const val KEY_PREFIX_PRIVKEY = "wg_privkey_"
    }
}
