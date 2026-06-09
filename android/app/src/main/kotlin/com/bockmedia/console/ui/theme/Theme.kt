package com.bockmedia.console.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val BockNavy = Color(0xFF30426A)
val BockGold = Color(0xFFE99D1A)
val BockMuted = Color(0xFF667085)
val BockBlack = Color(0xFF000000)
val BockSurface = BockBlack

// Home / mini bar (dark palette)
val HomeGradientTop = Color(0xFF141414)
val HomeGradientBottom = BockBlack
val HomePillInactive = Color(0xFF282828)
val HomePillActive = Color(0xFF5A7FB5)
val MiniBarTop = Color(0xFF181818)
val MiniBarBottom = BockBlack

private val AppColors = darkColorScheme(
    primary = Color(0xFF9BB4DC),
    onPrimary = Color(0xFF0E1524),
    primaryContainer = Color(0xFF35466A),
    onPrimaryContainer = Color(0xFFD8E4F8),
    secondary = BockGold,
    onSecondary = Color(0xFF1A1200),
    secondaryContainer = Color(0xFF4A3820),
    onSecondaryContainer = Color(0xFFFCEBD4),
    tertiary = Color(0xFF8FA4C8),
    background = BockBlack,
    surface = BockBlack,
    onBackground = Color(0xFFE8ECF4),
    onSurface = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFFA8B4C8),
    surfaceVariant = Color(0xFF1E1E1E),
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
