package com.bockmedia.console.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BockNavy = Color(0xFF30426A)
val BockGold = Color(0xFFE99D1A)
val BockMuted = Color(0xFF667085)
val BockSurface = Color(0xFFF4F6FB)

private val LightColors = lightColorScheme(
    primary = BockNavy,
    onPrimary = Color.White,
    secondary = BockGold,
    onSecondary = Color.White,
    background = BockSurface,
    surface = Color.White,
    onBackground = Color(0xFF1A2332),
    onSurface = Color(0xFF1A2332),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA4D6),
    onPrimary = Color(0xFF0E1524),
    secondary = BockGold,
    onSecondary = Color(0xFF1A1200),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A2332),
    onBackground = Color(0xFFE8ECF4),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF243044),
)

@Composable
fun BockMediaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
