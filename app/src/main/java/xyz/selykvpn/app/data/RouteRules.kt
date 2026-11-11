package xyz.selykvpn.app.data

import java.net.IDN

/**
 * User routing rules: what people type ("youtube.com", ".com", "https://site.ru/page", "1.2.3.4",
 * "10.0.0.0/8", "кремль.рф", "keyword:yandex", Clash's "DOMAIN-KEYWORD,yandex") becomes a stored
 * entry ("domain:youtube.com", "domain:.com", "keyword:yandex", "1.2.3.4", ...), and [toXray] turns
 * that into Xray's form. Anything Xray could choke on is rejected here, because one bad entry stops
 * the whole core.
 */
object RouteRules {

    enum class Kind { DOMAIN, KEYWORD, FULL, REGEXP, IP }

    /** Labels that buttons put into the text, so people only type the word itself. */
    val PREFIXES = listOf(Kind.KEYWORD to "keyword:", Kind.FULL to "full:", Kind.REGEXP to "regexp:")

    /**
     * [text] with [prefix] put over the selection [start]..[end] (on a new line unless the cursor
     * already starts an entry), and where the cursor goes: right after the prefix.
     */
    fun insertPrefix(text: String, start: Int, end: Int, prefix: String): Pair<String, Int> {
        val before = text.substring(0, start)
        val separator = if (before.isEmpty() || before.last() in "\n ,;\t") "" else "\n"
        val inserted = separator + prefix
        return before + inserted + text.substring(end) to start + inserted.length
    }

    /**
     * Clash / FLClash rule lines: "DOMAIN-KEYWORD,yandex", "DOMAIN-SUFFIX,ya.ru,DIRECT", "IP-CIDR,1.2.3.0/24".
     * Plain DOMAIN needs its comma: "domain:" is our own prefix (a site with subdomains).
     */
    private val CLASH_RULE = Regex(
        "^(?:(DOMAIN-SUFFIX|DOMAIN-KEYWORD|DOMAIN-REGEX|IP-CIDR6|IP-CIDR)\\s*[,:]|(DOMAIN)\\s*,)\\s*([^,\\s]+)",
        RegexOption.IGNORE_CASE,
    )

    val LAN_RANGES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10")
    val RU_DOMAINS = listOf("domain:ru", "domain:xn--p1ai", "domain:su")

    /**
     * Splits text by commas, spaces and new lines (a Clash rule line stays whole); returns normalized
     * valid entries and the rejected ones.
     */
    fun parseList(text: String): Pair<List<String>, List<String>> {
        val valid = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val tokens = if (CLASH_RULE.containsMatchIn(line)) listOf(line)
            else line.split(',', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            for (raw in tokens) {
                val entry = normalize(raw)
                if (entry != null) valid += entry else invalid += raw
            }
        }
        return valid.distinct() to invalid
    }

    /** Stored entry, or null when it is not a domain, keyword, IP or subnet. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        CLASH_RULE.find(s)?.let { m ->
            val value = m.groupValues[3]
            return when (m.groupValues[1].ifEmpty { m.groupValues[2] }.uppercase()) {
                "DOMAIN-SUFFIX" -> domain(value)
                "DOMAIN" -> host(value)?.let { "full:$it" }
                "DOMAIN-KEYWORD" -> keyword(value)
                "DOMAIN-REGEX" -> regexp(value)
                else -> value.takeIf { isIpOrCidr(it) }?.lowercase()
            }
        }
        val lower = s.lowercase()
        when {
            // Need geo databases the app doesn't ship.
            lower.startsWith("geosite:") || lower.startsWith("geoip:") || lower.startsWith("ext:") -> return null
            lower.startsWith("regexp:") -> return regexp(s.substringAfter(':'))
            lower.startsWith("keyword:") -> return keyword(s.substringAfter(':'))
            lower.startsWith("full:") -> return host(s.substringAfter(':'))?.let { "full:$it" }
            lower.startsWith("domain:") -> return domain(s.substringAfter(':'))
        }
        // Subnets first: "10.0.0.0/8" must not lose its prefix as if it were a URL path.
        if (isIpOrCidr(s)) return s.lowercase()
        if (Regex("^[0-9a-fA-F:.]+/\\d+$").matches(s)) return null
        if ("://" in s) s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (isIpOrCidr(s)) return s.lowercase()
        return domain(s)
    }

    /** The entry as Xray needs it: "domain:.com" (kept with its dot for the list) is "domain:com". */
    fun toXray(entry: String): String? = normalize(entry)?.let {
        if (it.startsWith("domain:.")) "domain:" + it.removePrefix("domain:.") else it
    }

    fun kindOf(entry: String): Kind = when {
        entry.startsWith("domain:") -> Kind.DOMAIN
        entry.startsWith("keyword:") -> Kind.KEYWORD
        entry.startsWith("full:") -> Kind.FULL
        entry.startsWith("regexp:") -> Kind.REGEXP
        else -> Kind.IP
    }

    /** What to show in the list: "youtube.com" for "domain:youtube.com", "yandex" for "keyword:yandex" (the kind is a label). */
    fun display(entry: String): String = when (kindOf(entry)) {
        Kind.IP -> entry
        Kind.REGEXP, Kind.KEYWORD -> entry.substringAfter(':')
        Kind.DOMAIN, Kind.FULL -> {
            val plain = entry.substringAfter(':')
            if (plain.startsWith("xn--") || ".xn--" in plain) runCatching { IDN.toUnicode(plain) }.getOrDefault(plain) else plain
        }
    }

    /** The entry as one would type it, for editing: ".com", "keyword:yandex", "1.2.3.4". */
    fun editable(entry: String): String = when (kindOf(entry)) {
        Kind.DOMAIN, Kind.IP -> display(entry)
        Kind.KEYWORD -> "keyword:" + display(entry)
        Kind.FULL -> "full:" + display(entry)
        Kind.REGEXP -> entry
    }

    /** "domain:" entry; a leading dot (".com", "*.site.ru") is kept so the list shows it as it was typed. */
    private fun domain(value: String): String? {
        val v = value.trim()
        val dot = v.startsWith(".") || v.startsWith("*.")
        return host(v)?.let { if (dot) "domain:.$it" else "domain:$it" }
    }

    private fun keyword(value: String): String? {
        val word = value.trim().lowercase()
        return if (word.isNotEmpty() && word.none { it.isWhitespace() || it == ',' }) "keyword:$word" else null
    }

    private fun regexp(pattern: String): String? =
        if (pattern.isNotEmpty() && runCatching { Regex(pattern) }.isSuccess) "regexp:$pattern" else null

    private fun host(value: String): String? {
        var h = value.trim().trimEnd('.').lowercase()
        if (h.startsWith("*.")) h = h.removePrefix("*.")
        if (h.startsWith(".")) h = h.removePrefix(".")
        // "host:443" -> "host"
        if (h.count { it == ':' } == 1) h = h.substringBefore(':')
        if (h.isEmpty() || h.length > 253) return null
        val ascii = runCatching { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        val labels = ascii.split('.')
        val labelOk = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
        // "300.1.1.1" is a broken IP, not a domain: top-level domains are never all digits.
        if (labels.last().all { it.isDigit() }) return null
        return if (labels.all { labelOk.matches(it) }) ascii else null
    }

    fun isIpOrCidr(value: String): Boolean {
        val ip = value.substringBefore('/')
        val prefix = value.substringAfter('/', "")
        if (prefix.isNotEmpty() && (prefix.length > 3 || prefix.any { !it.isDigit() })) return false
        return when {
            isIpv4(ip) -> prefix.isEmpty() || prefix.toInt() in 0..32
            isIpv6(ip) -> prefix.isEmpty() || prefix.toInt() in 0..128
            else -> false
        }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (value.isEmpty() || value.count { it == ':' } < 2) return false
        if (value.indexOf("::") != value.lastIndexOf("::")) return false
        var groups = value.split("::").flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
        var extra = 0
        // Embedded IPv4 at the end: "::ffff:1.2.3.4"
        if (groups.isNotEmpty() && '.' in groups.last()) {
            if (!isIpv4(groups.last())) return false
            groups = groups.dropLast(1)
            extra = 2
        }
        if (groups.any { g -> g.isEmpty() || g.length > 4 || g.any { it.digitToIntOrNull(16) == null } }) return false
        val total = groups.size + extra
        return if ("::" in value) total < 8 else total == 8
    }
}
