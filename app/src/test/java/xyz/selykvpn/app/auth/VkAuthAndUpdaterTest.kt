package xyz.selykvpn.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.selykvpn.app.data.UpdateCheckOutcome
import xyz.selykvpn.app.data.evaluateUpdateCheck
import xyz.selykvpn.app.update.UpdateProgress

class VkAuthAndUpdaterTest {

    @Test
    fun extractAccessTokenHandlesFragmentQueryAndQuotedJs() {
        assertEquals(
            "vk1.a.secret_token_123",
            VKCallHelper.extractAccessToken(
                "https://oauth.vk.com/blank.html#access_token=vk1.a.secret_token_123&expires_in=0&user_id=1"
            ),
        )
        assertEquals(
            "tok_quoted",
            VKCallHelper.extractAccessToken("\"https://oauth.vk.com/blank.html #access_token=tok_quoted&state=vkturn\""),
        )
        assertNull(VKCallHelper.extractAccessToken("https://m.vk.ru/login"))
        assertNull(VKCallHelper.extractAccessToken(""))
        assertNull(VKCallHelper.extractAccessToken(null))
    }

    @Test
    fun externalSchemesAreBlockedWhileWebSchemesRemainAllowed() {
        assertTrue(VKAuthActivity.shouldBlockScheme("vkauthorize"))
        assertTrue(VKAuthActivity.shouldBlockScheme("vkauth"))
        assertTrue(VKAuthActivity.shouldBlockScheme("vkontakte"))
        assertTrue(VKAuthActivity.shouldBlockScheme("vk-app"))
        assertTrue(VKAuthActivity.shouldBlockScheme("intent"))
        assertTrue(VKAuthActivity.shouldBlockScheme("tg"))
        assertTrue(VKAuthActivity.shouldBlockScheme("vk"))

        assertFalse(VKAuthActivity.shouldBlockScheme("https"))
        assertTrue(VKAuthActivity.shouldBlockScheme("http"))
        assertTrue(VKAuthActivity.shouldBlockScheme("about"))
        assertTrue(VKAuthActivity.shouldBlockScheme("javascript"))
        assertTrue(VKAuthActivity.shouldBlockScheme("data"))
    }

    @Test
    fun updateProgressSupportsIndeterminateUnknownLength() {
        val unknown = UpdateProgress(fraction = null, downloadedBytes = 1_048_576L, totalBytes = -1L)
        assertNull(unknown.fraction)
        assertEquals(1_048_576L, unknown.downloadedBytes)

        val known = UpdateProgress(fraction = 0.5f, downloadedBytes = 5_000_000L, totalBytes = 10_000_000L)
        assertEquals(0.5f, known.fraction!!, 0.001f)
    }

    @Test
    fun evaluateUpdateCheckDistinguishesAvailableFailedAndUpToDate() {
        assertEquals(UpdateCheckOutcome.AVAILABLE, evaluateUpdateCheck(refreshOk = true, hasUpdate = true))
        assertEquals(UpdateCheckOutcome.AVAILABLE, evaluateUpdateCheck(refreshOk = false, hasUpdate = true))
        assertEquals(UpdateCheckOutcome.FAILED, evaluateUpdateCheck(refreshOk = false, hasUpdate = false))
        assertEquals(UpdateCheckOutcome.UP_TO_DATE, evaluateUpdateCheck(refreshOk = true, hasUpdate = false))
    }

    @Test
    fun oauthAndLoggedInLandingClassifiersSeparateLoginProfileAndOAuth() {
        assertTrue(
            VKAuthActivity.isOAuthFlowUrl(
                "https://oauth.vk.com/authorize?client_id=6287487&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html&response_type=token"
            )
        )
        assertTrue(
            VKAuthActivity.isOAuthFlowUrl(
                "https://id.vk.com/auth?app_id=6287487&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html"
            )
        )
        assertTrue(VKAuthActivity.isOAuthFlowUrl("https://login.vk.com/?act=grant_access&hash=123"))

        // Plain VK ID login and account pages must NOT be treated as OAuth flow.
        assertFalse(VKAuthActivity.isOAuthFlowUrl("https://id.vk.ru/auth?redirect_uri=https%3A%2F%2Fm.vk.ru%2F"))
        assertFalse(VKAuthActivity.isOAuthFlowUrl("https://id.vk.ru/account"))
        assertFalse(VKAuthActivity.isOAuthFlowUrl("https://m.vk.ru/feed"))

        assertTrue(VKAuthActivity.isVkLoggedInLandingUrl("https://m.vk.ru/id12345678"))
        assertTrue(VKAuthActivity.isVkLoggedInLandingUrl("https://m.vk.ru/feed"))
        assertTrue(VKAuthActivity.isVkLoggedInLandingUrl("https://vk.ru/"))
        assertTrue(VKAuthActivity.isVkLoggedInLandingUrl("https://id.vk.ru/account"))
        assertTrue(VKAuthActivity.isVkLoggedInLandingUrl("https://vk.ru/calls"))

        assertFalse(VKAuthActivity.isVkLoggedInLandingUrl("https://m.vk.ru/login"))
        assertFalse(VKAuthActivity.isVkLoggedInLandingUrl("https://id.vk.ru/auth?redirect_uri=https%3A%2F%2Fm.vk.ru%2F"))
        assertFalse(VKAuthActivity.isVkLoggedInLandingUrl("https://oauth.vk.com/authorize?client_id=6287487"))
        assertFalse(VKAuthActivity.isVkLoggedInLandingUrl("https://oauth.vk.com/blank.html#access_token=abc"))
        assertFalse(VKAuthActivity.isVkLoggedInLandingUrl("https://vk.com/call/join/abc123"))
    }
}
