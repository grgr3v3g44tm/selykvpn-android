package xyz.selykvpn.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Which apps bypass the VPN: the user's own choice plus the server's recommended list. */
data class ExclusionSettings(
    val manual: Set<String>,
    val recommended: Set<String>,
    val useRecommended: Boolean,
    val overrides: Set<String>,
) {
    val effective: Set<String>
        get() = manual + (if (useRecommended) recommended - overrides else emptySet())

    fun isRecommended(pkg: String): Boolean = useRecommended && pkg in recommended
}

fun exclusionSettings(settings: SettingsStore, remoteConfig: RemoteConfigRepository): Flow<ExclusionSettings> =
    combine(
        settings.excludedApps,
        remoteConfig.state,
        settings.useRecommendedExclusions,
        settings.recommendedOverrides,
    ) { manual, config, useRecommended, overrides ->
        ExclusionSettings(manual, config.defaultExcludedApps.toSet(), useRecommended, overrides)
    }
