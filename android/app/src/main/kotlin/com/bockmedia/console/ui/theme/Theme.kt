package com.bockmedia.console.ui.theme

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

@Composable
fun BockMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
