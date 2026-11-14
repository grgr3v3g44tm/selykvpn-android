package xyz.selykvpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import xyz.selykvpn.app.R
import xyz.selykvpn.app.isTv
import xyz.selykvpn.app.ui.common.LinkQr

// 1. Selyk Premium brand colors: #B91226 Carmine, #F4E1C0 Soft Gold, #121316 Graphite
private val SelykDarkColors = darkColorScheme(
    primary = Color(0xFFB91226),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5C0710),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFF4E1C0),
    onSecondary = Color(0xFF3F2E18),
    secondaryContainer = Color(0xFF352B1E),
    onSecondaryContainer = Color(0xFFF4E1C0),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF003822),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE8E9ED),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE8E9ED),
    surfaceVariant = Color(0xFF2B2D34),
    onSurfaceVariant = Color(0xFFC7C9D1),
    outline = Color(0xFF7A7C85),
    outlineVariant = Color(0xFF373942),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF16171B),
    surfaceContainer = Color(0xFF1B1D22),
    surfaceContainerHigh = Color(0xFF22242A),
    surfaceContainerHighest = Color(0xFF2B2D34),
    inverseSurface = Color(0xFFE8E9ED),
    inverseOnSurface = Color(0xFF121316),
    inversePrimary = Color(0xFFB91226),
)

private val SelykLightColors = lightColorScheme(
    primary = Color(0xFFB91226),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF6E5B3E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7ECD8),
    onSecondaryContainer = Color(0xFF261906),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA7F3D0),
    onTertiaryContainer = Color(0xFF003822),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFBFC),
    onBackground = Color(0xFF181A1E),
    surface = Color(0xFFFBFBFC),
    onSurface = Color(0xFF181A1E),
    surfaceVariant = Color(0xFFE2E3E9),
    onSurfaceVariant = Color(0xFF464850),
    outline = Color(0xFF747680),
    outlineVariant = Color(0xFFC6C7D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4F7),
    surfaceContainer = Color(0xFFEDEEF2),
    surfaceContainerHigh = Color(0xFFE7E8ED),
    surfaceContainerHighest = Color(0xFFE1E2E7),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFF1F1F5),
    inversePrimary = Color(0xFFFFB4AB),
)

// 2. Original Cool Graphite palette: #1A1C1E background, #BDC7DC slate accent
private val GraphiteDarkColors = darkColorScheme(
    primary = Color(0xFFBDC7DC),
    onPrimary = Color(0xFF1A1C1E),
    primaryContainer = Color(0xFF3B4454),
    onPrimaryContainer = Color(0xFFD9E3F8),
    secondary = Color(0xFFBEC6D6),
    onSecondary = Color(0xFF28303C),
    secondaryContainer = Color(0xFF333A45),
    onSecondaryContainer = Color(0xFFDAE2F3),
    tertiary = Color(0xFF9FD8B3),
    onTertiary = Color(0xFF003920),
    tertiaryContainer = Color(0xFF1F4A33),
    onTertiaryContainer = Color(0xFFBAF4CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFF141617),
    surfaceContainerLow = Color(0xFF1E2023),
    surfaceContainer = Color(0xFF232529),
    surfaceContainerHigh = Color(0xFF282B31),
    surfaceContainerHighest = Color(0xFF33363B),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF535F72),
)

private val GraphiteLightColors = lightColorScheme(
    primary = Color(0xFF3E4A5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E3F8),
    onPrimaryContainer = Color(0xFF111C2B),
    secondary = Color(0xFF555F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F3),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF226B45),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBAF4CE),
    onTertiaryContainer = Color(0xFF002111),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E6),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFBDC7DC),
)

private val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

private val BaseTypography = Typography()

val SelykTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = GoogleSans),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = GoogleSans),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = GoogleSans),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = GoogleSans),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = GoogleSans),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = GoogleSans),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = GoogleSans),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = GoogleSans),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = GoogleSans),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = GoogleSans),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = GoogleSans),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = GoogleSans),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = GoogleSans),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = GoogleSans),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = GoogleSans),
)

@Composable
fun SelykVpnTheme(
    palette: String = "selyk",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        palette == "graphite" && darkTheme -> GraphiteDarkColors
        palette == "graphite" && !darkTheme -> GraphiteLightColors
        darkTheme -> SelykDarkColors
        else -> SelykLightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = SelykTypography,
        content = content,
    )
}

/** Backward compatibility alias */
@Composable
fun LegacyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    SelykVpnTheme(palette = "selyk", darkTheme = darkTheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvAdaptations(content: @Composable () -> Unit) {
    val context = LocalContext.current
    if (!remember { context.isTv() }) return content()
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(
            rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.32f, hoveredAlpha = 0.08f, pressedAlpha = 0.24f),
        ),
        LocalUriHandler provides remember {
            object : androidx.compose.ui.platform.UriHandler {
                override fun openUri(uri: String) {
                    val scheme = runCatching { android.net.Uri.parse(uri.trim()).scheme?.lowercase() }.getOrNull()
                    if (scheme in setOf("https", "http", "mailto", "tg")) {
                        LinkQr.show(uri)
                    }
                }
            }
        },
        content = content,
    )
}
