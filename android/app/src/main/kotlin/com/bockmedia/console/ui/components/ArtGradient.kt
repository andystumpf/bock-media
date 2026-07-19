package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val GRADIENT_PALETTE = ACCENT_GRADIENT_PALETTE

fun artGradientFor(title: String): Brush {
    val pair = GRADIENT_PALETTE[kotlin.math.abs(title.hashCode()) % GRADIENT_PALETTE.size]
    return Brush.linearGradient(pair)
}

fun artInitials(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

@Composable
fun ArtGradientFallback(
    title: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
) {
    Box(
        modifier = modifier.background(artGradientFor(title)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            artInitials(title),
            color = Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
