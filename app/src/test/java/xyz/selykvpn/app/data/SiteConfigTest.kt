package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteConfigTest {

    @Test
    fun defaultsWhenApiHasNoSite() {
        val config = AppJson.decodeFromString<RemoteConfig>("{}")
        assertEquals(SiteConfig("tgapp.selykvpn.xyz", "tgapp.selykvpn.xyz"), config.site)
        assertEquals("https://tgapp.selykvpn.xyz/dashboard/install", config.site.installUrl)
    }

    @Test
    fun domainFromApi() {
        val config = AppJson.decodeFromString<RemoteConfig>("""{"site":{"domain":"selykvpn2.xyz"}}""")
        assertEquals(SiteConfig("selykvpn2.xyz", "tgapp.selykvpn.xyz"), config.site.sanitized())
        assertEquals("https://selykvpn2.xyz/dashboard/install", config.site.sanitized().installUrl)
    }

    @Test
    fun urlsBecomeHostNames() {
        assertEquals(
            SiteConfig("selykvpn.xyz", "api.selykvpn.xyz"),
            SiteConfig(" https://SelykVPN.xyz/ ", "https://api.selykvpn.xyz/v1?x=1").sanitized(),
        )
        assertEquals("lk.selykvpn.xyz:8443", SiteConfig("lk.selykvpn.xyz:8443").sanitized().domain)
        assertEquals("xn--e1akcw1e.xn--p1ai", SiteConfig("селюк.рф").sanitized().domain)
    }

    @Test
    fun garbageFallsBackToDefaults() {
        listOf("", "  ", "selykvpn", "javascript:alert(1)", "evil.com\"><script>", "user@selykvpn.info", "1.2.3.4").forEach {
            assertEquals(it, SiteConfig(), SiteConfig(it, it).sanitized())
        }
    }

    @Test
    fun renewalPageOfTheSubscription() {
        assertEquals("https://tgapp.selykvpn.xyz/renewal?sub=abcDEF123456XYZ0", SiteConfig().renewalUrl("abcDEF123456XYZ0"))
        assertEquals("https://selykvpn2.xyz/renewal?sub=a%2Fb%26c", SiteConfig("selykvpn2.xyz").renewalUrl("a/b&c"))
    }
}
