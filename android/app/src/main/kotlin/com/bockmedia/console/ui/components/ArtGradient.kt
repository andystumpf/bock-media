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

private val GRADIENT_PALETTE = listOf(
    listOf(Color(0xFF450A0A), Color(0xFF7F1D1D)),
    listOf(Color(0xFF431407), Color(0xFF9A3412)),
    listOf(Color(0xFF422006), Color(0xFF854D0E)),
    listOf(Color(0xFF14532D), Color(0xFF166534)),
    listOf(Color(0xFF064E3B), Color(0xFF047857)),
    listOf(Color(0xFF164E63), Color(0xFF0E7490)),
    listOf(Color(0xFF1E3A8A), Color(0xFF1D4ED8)),
    listOf(Color(0xFF312E81), Color(0xFF4338CA)),
    listOf(Color(0xFF581C87), Color(0xFF7E22CE)),
    listOf(Color(0xFF701A75), Color(0xFFA21CAF)),
    listOf(Color(0xFF831843), Color(0xFFBE185D)),
    listOf(Color(0xFF1F2937), Color(0xFF374151)),
)

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
