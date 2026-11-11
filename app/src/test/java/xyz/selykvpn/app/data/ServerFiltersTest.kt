package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerFiltersTest {
    private fun server(remarks: String) = ServerEntry(remarks, remarks, null, remarks, ServerKind.SERVER)
    private fun header(title: String) = ServerEntry(title, title, null, title, ServerKind.SEPARATOR)

    private val servers = listOf(
        header("Автовыбор"),
        ServerEntry("auto", "🇪🇺 Автовыбор Wi-Fi 🎲", "🇪🇺", "Автовыбор Wi-Fi 🎲", ServerKind.AUTO),
        header("Домашние (Wi-Fi)"),
        server("🇳🇱 Нидерланды ⚡🤖📺🏴‍☠️"), // no variation selectors
        server("🇩🇪 Германия ⚡🤖🎮"),
        server("🇺🇸 США 🤖"),
        header("Обходы (LTE)"),
        server("🇷🇺🦊 Все операторы ⚡️ (-Йота)"), // with a variation selector
    )

    @Test
    fun emojiVariantsMatch() {
        val reliable = ServerFilters.apply(servers, "⚡️").map { it.remarks }
        assertEquals(
            listOf("Домашние (Wi-Fi)", "🇳🇱 Нидерланды ⚡🤖📺🏴‍☠️", "🇩🇪 Германия ⚡🤖🎮", "Обходы (LTE)", "🇷🇺🦊 Все операторы ⚡️ (-Йота)"),
            reliable,
        )
        assertEquals(listOf("Домашние (Wi-Fi)", "🇳🇱 Нидерланды ⚡🤖📺🏴‍☠️"), ServerFilters.apply(servers, "🏴‍☠️").map { it.remarks })
        assertEquals(servers, ServerFilters.apply(servers, null))
    }

    @Test
    fun onlyTabsWithServersAreShown() {
        val filters = listOf(ServerFilter("⚡️", "Надёжный"), ServerFilter("🎮", "Игровой"), ServerFilter("🦄", "Нет таких"))
        assertEquals(listOf("⚡️", "🎮"), ServerFilters.available(filters, servers).map { it.emoji })
    }
}
