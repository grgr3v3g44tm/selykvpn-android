package xyz.selykvpn.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** What a settings file holds: routing or apps without VPN, each exported on its own. */
enum class TransferKind(val fileName: String) {
    ROUTING("selyk-routing.json"),
    APPS("selyk-apps.json"),
}

data class AppsImport(val useRecommended: Boolean, val excluded: Set<String>, val returnedToVpn: Set<String>)

/** A checked settings file, ready to apply. [skipped]: entries that were not valid and were left out. */
sealed interface SettingsImport {
    val skipped: Int

    data class Routing(val settings: RoutingSettings, override val skipped: Int) : SettingsImport
    data class Apps(val apps: AppsImport, override val skipped: Int) : SettingsImport
}

class SettingsFileException : Exception("not a Селюк VPN settings file")

/**
 * Settings files that can simply be sent to someone: JSON with a "type", so the app recognizes
 * them when they are opened from a messenger. Everything read from a file is validated the same
 * way as typed entries, because one bad routing entry would stop the whole core.
 */
object SettingsTransfer {
    private const val TYPE_ROUTING = "selyk-routing"
    private const val TYPE_APPS = "selyk-apps"
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class RoutingFile(
        val type: String = TYPE_ROUTING,
        val version: Int = 1,
        val enabled: Boolean = true,
        val lanDirect: Boolean = true,
        val ruDirect: Boolean = false,
        val direct: List<String> = emptyList(),
        val proxy: List<String> = emptyList(),
        val block: List<String> = emptyList(),
    )

    @Serializable
    private data class AppsFile(
        val type: String = TYPE_APPS,
        val version: Int = 1,
        val useRecommended: Boolean = true,
        /** Apps the user took out of the VPN. */
        val excluded: List<String> = emptyList(),
        /** Apps from the server's recommended list the user put back into the VPN. */
        @SerialName("returnedToVpn") val returned: List<String> = emptyList(),
    )

    fun exportRouting(routing: RoutingSettings): String = json.encodeToString(
        RoutingFile.serializer(),
        RoutingFile(
            enabled = routing.enabled,
            lanDirect = routing.lanDirect,
            ruDirect = routing.ruDirect,
            direct = routing.direct.sorted(),
            proxy = routing.proxy.sorted(),
            block = routing.block.sorted(),
        ),
    )

    fun exportApps(exclusions: ExclusionSettings): String = json.encodeToString(
        AppsFile.serializer(),
        AppsFile(
            useRecommended = exclusions.useRecommended,
            excluded = exclusions.manual.sorted(),
            returned = exclusions.overrides.sorted(),
        ),
    )

    /** @throws SettingsFileException when the text is not one of the app's settings files. */
    fun parse(text: String): SettingsImport {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw SettingsFileException()
        return try {
            when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
                TYPE_ROUTING -> routing(json.decodeFromJsonElement<RoutingFile>(root))
                TYPE_APPS -> apps(json.decodeFromJsonElement<AppsFile>(root))
                else -> throw SettingsFileException()
            }
        } catch (e: SettingsFileException) {
            throw e
        } catch (_: Exception) {
            throw SettingsFileException()
        }
    }

    private fun routing(file: RoutingFile): SettingsImport.Routing {
        var skipped = 0
        val taken = HashSet<String>()
        // An address in several lists stays where it would take effect: block, then direct, then VPN.
        fun clean(entries: List<String>): Set<String> {
            val result = LinkedHashSet<String>()
            for (raw in entries) {
                val entry = RouteRules.normalize(raw)
                when {
                    entry == null || result.size >= MAX_ENTRIES -> skipped++
                    taken.add(entry) -> result += entry
                }
            }
            return result
        }
        val block = clean(file.block)
        val direct = clean(file.direct)
        val proxy = clean(file.proxy)
        return SettingsImport.Routing(
            RoutingSettings(file.enabled, file.lanDirect, file.ruDirect, direct = direct, proxy = proxy, block = block),
            skipped,
        )
    }

    private fun apps(file: AppsFile): SettingsImport.Apps {
        var skipped = 0
        fun clean(packages: List<String>): Set<String> {
            val result = LinkedHashSet<String>()
            for (raw in packages) {
                val pkg = raw.trim()
                if (PACKAGE.matches(pkg) && result.size < MAX_ENTRIES) result += pkg else skipped++
            }
            return result
        }
        val excluded = clean(file.excluded)
        val returned = clean(file.returned) - excluded
        return SettingsImport.Apps(AppsImport(file.useRecommended, excluded, returned), skipped)
    }
}
