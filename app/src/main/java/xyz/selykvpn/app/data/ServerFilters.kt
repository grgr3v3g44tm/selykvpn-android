package xyz.selykvpn.app.data

/** Server tabs by feature emoji in the server names ("⚡️ — Надёжный", "🎮 — Игровой", ...). */
object ServerFilters {
    // Names mix "⚡" and "⚡️": compare without the emoji variation selector.
    private fun normalize(text: String) = text.replace("️", "")

    fun matches(entry: ServerEntry, emoji: String): Boolean =
        entry.kind != ServerKind.SEPARATOR && normalize(entry.remarks).contains(normalize(emoji))

    /** Tabs that have at least one server in this subscription, in the configured order. */
    fun available(filters: List<ServerFilter>, servers: List<ServerEntry>): List<ServerFilter> =
        filters.filter { filter -> servers.any { matches(it, filter.emoji) } }

    /** Servers with the emoji; a section header is kept only if something below it matches. */
    fun apply(servers: List<ServerEntry>, emoji: String?): List<ServerEntry> {
        if (emoji == null) return servers
        val result = mutableListOf<ServerEntry>()
        var header: ServerEntry? = null
        for (entry in servers) {
            if (entry.kind == ServerKind.SEPARATOR) {
                header = entry
            } else if (matches(entry, emoji)) {
                header?.let { result += it }
                header = null
                result += entry
            }
        }
        return result
    }
}
