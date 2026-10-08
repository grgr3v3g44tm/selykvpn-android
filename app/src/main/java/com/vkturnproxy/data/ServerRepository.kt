package com.vkturnproxy.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vkturnproxy.model.ServerConfig
import com.vkturnproxy.parser.ConfigParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vkturn_servers", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _serversFlow = MutableStateFlow<List<ServerConfig>>(emptyList())
    val serversFlow = _serversFlow.asStateFlow()

    private val _activeServerFlow = MutableStateFlow<ServerConfig?>(null)
    val activeServerFlow = _activeServerFlow.asStateFlow()

    init {
        loadServers()
    }

    private fun loadServers() {
        val json = prefs.getString("servers_json", null)
        val list: List<ServerConfig> = if (json != null) {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            try {
                gson.fromJson(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        _serversFlow.value = list
        val activeId = prefs.getString("active_server_id", null)
        _activeServerFlow.value = list.find { it.id == activeId } ?: list.firstOrNull()
        saveServers(list)
    }

    fun saveServer(config: ServerConfig) {
        val current = _serversFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            current[index] = config
        } else {
            current.add(config)
        }
        saveServers(current)
        if (_activeServerFlow.value?.id == config.id || _activeServerFlow.value == null) {
            setActiveServer(config)
        }
    }

    fun deleteServer(id: String) {
        val current = _serversFlow.value.toMutableList()
        current.removeAll { it.id == id }
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
        _serversFlow.value = list
        prefs.edit().putString("servers_json", gson.toJson(list)).apply()
    }
}
