package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsTransferTest {

    @Test
    fun routingRoundTrip() {
        val routing = RoutingSettings(
            enabled = false,
            lanDirect = false,
            ruDirect = true,
            direct = setOf("domain:superhub.host", "10.0.0.0/8"),
            proxy = setOf("domain:youtube.com"),
            block = setOf("full:ads.example.com", "keyword:tracker"),
        )
        val parsed = SettingsTransfer.parse(SettingsTransfer.exportRouting(routing)) as SettingsImport.Routing
        assertEquals(routing, parsed.settings)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun routingFromFileIsValidated() {
        val text = """
            {"type": "selyk-routing", "version": 1, "something": "new",
             "direct": ["superhub.host", "https://кремль.рф/page", "300.1.1.1", "geosite:ru"],
             "proxy": ["1.2.3.4", "1.2.3.4"],
             "block": ["SuperHub.host"]}
        """
        val parsed = SettingsTransfer.parse(text) as SettingsImport.Routing
        // Block wins over direct for the same address; duplicates are not errors.
        assertEquals(setOf("domain:superhub.host"), parsed.settings.block)
        assertEquals(setOf("domain:xn--e1ajeds9e.xn--p1ai"), parsed.settings.direct)
        assertEquals(setOf("1.2.3.4"), parsed.settings.proxy)
        assertEquals(2, parsed.skipped)
        // Missing fields keep the defaults.
        assertEquals(true, parsed.settings.enabled)
        assertEquals(true, parsed.settings.lanDirect)
    }

    @Test
    fun appsRoundTrip() {
        val exclusions = ExclusionSettings(
            manual = setOf("org.telegram.messenger", "com.whatsapp"),
            recommended = setOf("ru.oneme.app", "ru.rostel"),
            useRecommended = false,
            overrides = setOf("ru.oneme.app"),
        )
        val parsed = SettingsTransfer.parse(SettingsTransfer.exportApps(exclusions)) as SettingsImport.Apps
        assertEquals(AppsImport(false, setOf("org.telegram.messenger", "com.whatsapp"), setOf("ru.oneme.app")), parsed.apps)
    }

    @Test
    fun appsFromFileAreValidated() {
        val text = """{"type":"selyk-apps","excluded":["org.telegram.messenger"," com.whatsapp ","not a package","x","1abc.def"],"returnedToVpn":["org.telegram.messenger","ru.rostel"]}"""
        val parsed = SettingsTransfer.parse(text) as SettingsImport.Apps
        assertEquals(setOf("org.telegram.messenger", "com.whatsapp"), parsed.apps.excluded)
        assertEquals(setOf("ru.rostel"), parsed.apps.returnedToVpn)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun foreignFilesAreRejected() {
        listOf("", "not json", "[]", "{}", """{"type":"other"}""", """{"type":"selyk-routing","direct":"x"}""").forEach { text ->
            assertThrows(text, SettingsFileException::class.java) { SettingsTransfer.parse(text) }
        }
    }
}
