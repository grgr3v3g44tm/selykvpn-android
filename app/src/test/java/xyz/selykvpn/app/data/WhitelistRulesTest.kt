package xyz.selykvpn.app.data

import xyz.selykvpn.app.data.WhitelistStatus.ALL_OK
import xyz.selykvpn.app.data.WhitelistStatus.NO_INTERNET
import xyz.selykvpn.app.data.WhitelistStatus.UNKNOWN
import xyz.selykvpn.app.data.WhitelistStatus.WHITELIST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistRulesTest {
    @Test
    fun onlyBypassServersArePingedUnderWhiteLists() {
        val config = WhitelistConfig()
        fun server(name: String) = ServerEntry(name, name, null, name, ServerKind.SERVER)
        assertTrue(WhitelistRules.worksUnderWhitelist(server("🇷🇺🦊 Все операторы ⚡️ (-Йота)"), config))
        assertTrue(WhitelistRules.worksUnderWhitelist(server("🇷🇺 Москва LTE"), config))
        assertFalse(WhitelistRules.worksUnderWhitelist(server("🇳🇱 Нидерланды ⚡🤖"), config))
    }

    private fun server(name: String, kind: ServerKind = ServerKind.SERVER) = ServerEntry("$name#1", name, null, name, kind)

    private val wifiAuto = server("🇪🇺 Автовыбор Wi-Fi 🎲", ServerKind.AUTO)
    private val lteAuto = server("🇷🇺 Автовыбор LTE 🎲", ServerKind.AUTO)
    private val nl = server("🇳🇱 Нидерланды ⚡")
    private val bypass = server("🇷🇺🦊 Все операторы ⚡️ (-Йота)")
    private val servers = listOf(wifiAuto, lteAuto, nl, bypass)
    private val config = WhitelistConfig()

    @Test
    fun classify() {
        assertEquals(ALL_OK, WhitelistRules.classify(blockedOk = true, allowedOk = true))
        assertEquals(WHITELIST, WhitelistRules.classify(blockedOk = false, allowedOk = true))
        assertEquals(NO_INTERNET, WhitelistRules.classify(blockedOk = false, allowedOk = false))
    }

    @Test
    fun whiteListsOnMoveToLteUnlessBypassChosen() {
        assertEquals(lteAuto, WhitelistRules.target(ALL_OK, WHITELIST, nl, servers, config, null))
        assertEquals(lteAuto, WhitelistRules.target(UNKNOWN, WHITELIST, wifiAuto, servers, config, null))
        assertNull(WhitelistRules.target(ALL_OK, WHITELIST, bypass, servers, config, null))
        assertNull(WhitelistRules.target(ALL_OK, WHITELIST, lteAuto, servers, config, null))
        assertNull(WhitelistRules.target(WHITELIST, WHITELIST, nl, servers, config, null)) // only on change
    }

    @Test
    fun whiteListsOffMoveBackOnlyFromBypassOrOwnChoice() {
        assertEquals(wifiAuto, WhitelistRules.target(WHITELIST, ALL_OK, lteAuto, servers, config, lteAuto.key))
        assertEquals(wifiAuto, WhitelistRules.target(WHITELIST, ALL_OK, bypass, servers, config, null))
        assertEquals(wifiAuto, WhitelistRules.target(UNKNOWN, ALL_OK, lteAuto, servers, config, lteAuto.key))
        assertNull(WhitelistRules.target(WHITELIST, ALL_OK, nl, servers, config, null)) // picked by hand
        assertNull(WhitelistRules.target(UNKNOWN, ALL_OK, lteAuto, servers, config, null)) // picked by hand
        assertNull(WhitelistRules.target(UNKNOWN, ALL_OK, bypass, servers, config, null)) // bypass on a normal network by choice
    }
}
