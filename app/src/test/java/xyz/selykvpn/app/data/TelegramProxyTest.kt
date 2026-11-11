package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramProxyTest {

    @Test
    fun onlyTelegramLinksAreKept() {
        val config = AppJson.decodeFromString<RemoteConfig>(
            """{"telegramProxies":[
              {"title":"Польша","flag":"🇵🇱","url":"https://t.me/webproxy?server=a.net%2Fx&secret=s"},
              {"title":"MTProto","url":"tg://proxy?server=1.2.3.4&port=443&secret=dd00"},
              {"title":"Чужой","url":"https://evil.example/t.me/proxy"},
              {"title":"","url":"https://t.me/proxy?server=b"},
              {"title":"Unknown field","url":"https://t.me/socks?server=c&port=1080","extra":1}
            ]}""",
        )
        assertEquals(listOf("Польша", "MTProto", "Unknown field"), config.telegramProxies.filter { it.valid }.map { it.title })
    }

    @Test
    fun noProxiesByDefault() {
        assertEquals(emptyList<TelegramProxy>(), AppJson.decodeFromString<RemoteConfig>("{}").telegramProxies)
    }
}
