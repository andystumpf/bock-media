package com.bockmedia.console.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val BockGreen = Color(0xFF1DB954)
val BockNavy = Color(0xFF30426A)
val BockGold = Color(0xFFE99D1A)
val BockMuted = Color(0xFFB3B3B3)
val BockBlack = Color(0xFF000000)
val BockSurface = Color(0xFF121212)

// Spotify-style home palette
val SpotifyBackground = Color(0xFF121212)
val SpotifyElevated = Color(0xFF282828)
val SpotifyShortcutHover = Color(0xFF3E3E3E)
val HomeGradientTop = Color(0x593D5A45)
val HomeGradientBottom = SpotifyBackground
val HomePillInactive = SpotifyElevated
val HomePillActive = BockGreen
val MiniBarTop = Color(0xFF181818)
val MiniBarBottom = BockBlack

val MixAccentColors = listOf(
    Color(0xFF5038A0),
    Color(0xFF8D67AB),
    Color(0xFF509BF5),
    Color(0xFFE91429),
    Color(0xFF1DB954),
    Color(0xFFE8115B),
    Color(0xFF148A08),
    Color(0xFFD84000),
)

fun mixAccentColor(seed: String): Color {
    val idx = (seed.hashCode() and Int.MAX_VALUE) % MixAccentColors.size
    return MixAccentColors[idx]
}

private val AppColors = darkColorScheme(
    primary = BockGreen,
    onPrimary = Color(0xFF0F1419),
    primaryContainer = Color(0xFF1A3D2A),
    onPrimaryContainer = Color(0xFFB8F0CE),
    secondary = BockGold,
    onSecondary = Color(0xFF1A1200),
    secondaryContainer = Color(0xFF4A3820),
    onSecondaryContainer = Color(0xFFFCEBD4),
    tertiary = Color(0xFF5FBF82),
    background = SpotifyBackground,
    surface = SpotifyBackground,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = BockMuted,
    surfaceVariant = Color(0xFF181818),
)

@Composable
fun bockIsDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun BockMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content,
    )
}
