package com.vkturnproxy

import android.app.Application
import com.vkturnproxy.data.ServerRepository
import com.vkturnproxy.service.SplitTunnelManager

class VkTurnApp : Application() {

    lateinit var serverRepository: ServerRepository
        private set

    lateinit var splitTunnelManager: SplitTunnelManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        serverRepository = ServerRepository(this)
        splitTunnelManager = SplitTunnelManager(this)
        com.vkturnproxy.auth.VKCookieStore.init(this)
    }

    companion object {
        lateinit var instance: VkTurnApp
            private set
    }
}
