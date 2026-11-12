package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistRulesTest {

    @Test
    fun classifyMapsProbeResultsToExpectedStatuses() {
        assertEquals(WhitelistStatus.ALL_OK, WhitelistRules.classify(blockedOk = true, allowedOk = true))
        assertEquals(WhitelistStatus.ALL_OK, WhitelistRules.classify(blockedOk = true, allowedOk = false))
        assertEquals(WhitelistStatus.WHITELIST, WhitelistRules.classify(blockedOk = false, allowedOk = true))
        assertEquals(WhitelistStatus.NO_INTERNET, WhitelistRules.classify(blockedOk = false, allowedOk = false))
    }

    @Test
    fun switchesFromRegularToFreeAccessWhenAllowedAndReady() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.ALL_OK,
            new = WhitelistStatus.WHITELIST,
            currentMode = "regular",
            freeAccessAllowed = true,
            freeAccessReady = true,
            autoSwitchedMarker = null,
        )
        assertEquals(ModeSwitchDecision.TO_FREE_ACCESS, decision)
    }

    @Test
    fun emitsSetupRequiredWhenFreeAccessAllowedButNotReady() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.ALL_OK,
            new = WhitelistStatus.WHITELIST,
            currentMode = "regular",
            freeAccessAllowed = true,
            freeAccessReady = false,
            autoSwitchedMarker = null,
        )
        assertEquals(ModeSwitchDecision.SETUP_REQUIRED, decision)
    }

    @Test
    fun emitsTariffLockedWhenFreeAccessDisallowedByTariff() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.ALL_OK,
            new = WhitelistStatus.WHITELIST,
            currentMode = "regular",
            freeAccessAllowed = false,
            freeAccessReady = true,
            autoSwitchedMarker = null,
        )
        assertEquals(ModeSwitchDecision.TARIFF_LOCKED, decision)
    }

    @Test
    fun doesNothingOnWhitelistWhenAlreadyInFreeAccess() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.ALL_OK,
            new = WhitelistStatus.WHITELIST,
            currentMode = "free_access",
            freeAccessAllowed = true,
            freeAccessReady = true,
            autoSwitchedMarker = null,
        )
        assertEquals(ModeSwitchDecision.NONE, decision)
    }

    @Test
    fun returnsToRegularWhenNetworkRecoversAfterAutoSwitch() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.WHITELIST,
            new = WhitelistStatus.ALL_OK,
            currentMode = "free_access",
            freeAccessAllowed = true,
            freeAccessReady = true,
            autoSwitchedMarker = WhitelistRules.AUTO_MODE_FREE_ACCESS,
        )
        assertEquals(ModeSwitchDecision.TO_REGULAR, decision)
    }

    @Test
    fun keepsManualFreeAccessWhenNetworkRecovers() {
        val decision = WhitelistRules.targetMode(
            old = WhitelistStatus.WHITELIST,
            new = WhitelistStatus.ALL_OK,
            currentMode = "free_access",
            freeAccessAllowed = true,
            freeAccessReady = true,
            autoSwitchedMarker = null,
        )
        assertEquals(ModeSwitchDecision.NONE, decision)
    }

    @Test
    fun ignoresTransientNoInternet() {
        assertEquals(
            ModeSwitchDecision.NONE,
            WhitelistRules.targetMode(
                old = WhitelistStatus.WHITELIST,
                new = WhitelistStatus.NO_INTERNET,
                currentMode = "free_access",
                freeAccessAllowed = true,
                freeAccessReady = true,
                autoSwitchedMarker = WhitelistRules.AUTO_MODE_FREE_ACCESS,
            ),
        )
        assertEquals(
            ModeSwitchDecision.NONE,
            WhitelistRules.targetMode(
                old = WhitelistStatus.NO_INTERNET,
                new = WhitelistStatus.ALL_OK,
                currentMode = "regular",
                freeAccessAllowed = true,
                freeAccessReady = true,
                autoSwitchedMarker = null,
            ),
        )
    }
}
