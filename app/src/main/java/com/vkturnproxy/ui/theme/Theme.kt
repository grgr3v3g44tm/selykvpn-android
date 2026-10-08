package com.vkturnproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0B0F19)
val SurfaceDark = Color(0xFF131B2E)
val SurfaceVariantDark = Color(0xFF1E293B)
val PrimaryBlue = Color(0xFF38BDF8)
val PrimaryGlow = Color(0xFF0284C7)
val AccentGreen = Color(0xFF10B981)
val AccentAmber = Color(0xFFF59E0B)
val AccentRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.Black,
    primaryContainer = PrimaryGlow,
    onPrimaryContainer = Color.White,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = AccentRed
)

@Composable
fun VkTurnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
