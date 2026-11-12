package xyz.selykvpn.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertFalse(VKAuthActivity.shouldBlockScheme("http"))
        assertFalse(VKAuthActivity.shouldBlockScheme("about"))
    }

    @Test
    fun updateProgressSupportsIndeterminateUnknownLength() {
        val unknown = UpdateProgress(fraction = null, downloadedBytes = 1_048_576L, totalBytes = -1L)
        assertNull(unknown.fraction)
        assertEquals(1_048_576L, unknown.downloadedBytes)

        val known = UpdateProgress(fraction = 0.5f, downloadedBytes = 5_000_000L, totalBytes = 10_000_000L)
        assertEquals(0.5f, known.fraction!!, 0.001f)
    }
}
