package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRulesTest {
    @Test
    fun whatPeopleTypeBecomesARule() {
        assertEquals("domain:youtube.com", RouteRules.normalize("youtube.com"))
        assertEquals("domain:youtube.com", RouteRules.normalize("https://www.youtube.com/watch?v=1".replace("www.", "")))
        assertEquals("domain:.site.ru", RouteRules.normalize("*.site.ru"))
        assertEquals("domain:site.ru", RouteRules.normalize("site.ru:443"))
        assertEquals("domain:xn--e1ajeds9e.xn--p1ai", RouteRules.normalize("кремль.рф"))
        assertEquals("кремль.рф", RouteRules.display("domain:xn--e1ajeds9e.xn--p1ai"))
        assertEquals("1.2.3.4", RouteRules.normalize("1.2.3.4"))
        assertEquals("10.0.0.0/8", RouteRules.normalize("10.0.0.0/8"))
        assertEquals("2001:db8::/32", RouteRules.normalize("2001:DB8::/32"))
        assertEquals("keyword:tiktok", RouteRules.normalize("keyword:TikTok"))
        assertEquals("full:api.example.com", RouteRules.normalize("full:api.example.com"))
    }

    @Test
    fun leadingDotIsKeptForTheListButNotForXray() {
        assertEquals("domain:.com", RouteRules.normalize(".com"))
        assertEquals(".com", RouteRules.display("domain:.com"))
        assertEquals("domain:com", RouteRules.toXray("domain:.com"))
        assertEquals("domain:youtube.com", RouteRules.toXray("domain:youtube.com"))
        assertEquals(".рф", RouteRules.display(RouteRules.normalize(".рф")!!))
        assertEquals("domain:.xn--p1ai", RouteRules.normalize(".рф"))
        // Saved entries go through normalize again when imported or built.
        assertEquals("domain:.com", RouteRules.normalize("domain:.com"))
    }

    @Test
    fun keywordsAndClashRules() {
        assertEquals("keyword:yandex", RouteRules.normalize("KEYWORD:yandex"))
        assertEquals("keyword:yandex", RouteRules.normalize("DOMAIN-KEYWORD,yandex"))
        assertEquals("keyword:yandex", RouteRules.normalize("domain-keyword:Yandex"))
        assertEquals("domain:ya.ru", RouteRules.normalize("DOMAIN-SUFFIX,ya.ru,DIRECT"))
        assertEquals("full:api.example.com", RouteRules.normalize("DOMAIN,api.example.com"))
        assertEquals("1.2.3.0/24", RouteRules.normalize("IP-CIDR,1.2.3.0/24,no-resolve"))
        assertNull(RouteRules.normalize("IP-CIDR,300.1.1.0/24"))
        // Our own "domain:" prefix is not Clash's DOMAIN (exact match).
        assertEquals("domain:example.com", RouteRules.normalize("domain:example.com"))
        assertEquals(RouteRules.Kind.KEYWORD, RouteRules.kindOf("keyword:yandex"))
        assertEquals("yandex", RouteRules.display("keyword:yandex"))
        assertEquals("keyword:yandex", RouteRules.editable("keyword:yandex"))
        assertEquals(".com", RouteRules.editable("domain:.com"))
        val (valid, invalid) = RouteRules.parseList("DOMAIN-KEYWORD,yandex\nDOMAIN-SUFFIX,vk.com,DIRECT\nkeyword:ozon, .su")
        assertEquals(listOf("keyword:yandex", "domain:vk.com", "keyword:ozon", "domain:.su"), valid)
        assertEquals(emptyList<String>(), invalid)
    }

    @Test
    fun labelButtonsInsertAtTheCursor() {
        assertEquals("keyword:" to 8, RouteRules.insertPrefix("", 0, 0, "keyword:"))
        // After another entry: on a new line.
        assertEquals("ya.ru\nkeyword:" to 14, RouteRules.insertPrefix("ya.ru", 5, 5, "keyword:"))
        assertEquals("ya.ru, keyword:" to 15, RouteRules.insertPrefix("ya.ru, ", 7, 7, "keyword:"))
        // Over a selection.
        assertEquals("full:x.com" to 5, RouteRules.insertPrefix("yandex x.com", 0, 7, "full:"))
    }

    @Test
    fun badEntriesAreRejected() {
        assertNull(RouteRules.normalize("geosite:google")) // no geo databases in the app
        assertNull(RouteRules.normalize("300.1.1.1/8"))
        assertNull(RouteRules.normalize("300.1.1.1"))
        assertEquals("1.2.3.4", RouteRules.normalize("http://1.2.3.4/page"))
        assertNull(RouteRules.normalize("1.2.3.4/33"))
        assertNull(RouteRules.normalize("not a domain!"))
        assertNull(RouteRules.normalize("regexp:("))
        assertFalse(RouteRules.isIpOrCidr("1:2:3"))
        assertTrue(RouteRules.isIpOrCidr("::ffff:1.2.3.4"))
        val (valid, invalid) = RouteRules.parseList("youtube.com, 1.1.1.1\n bad_host!, x.org")
        assertEquals(listOf("domain:youtube.com", "1.1.1.1", "domain:x.org"), valid)
        assertEquals(listOf("bad_host!"), invalid)
    }
}
