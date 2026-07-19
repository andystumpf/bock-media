package com.bockmedia.console.ui.components

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun gradientAccentColor(seed: String): Color {
    val pair = ACCENT_GRADIENT_PALETTE[kotlin.math.abs(seed.hashCode()) % ACCENT_GRADIENT_PALETTE.size]
    return pair[0]
}

fun heroGradientBrush(accent: Color): Brush = Brush.verticalGradient(
    0f to accent,
    0.45f to Color(0xFF121212),
    1f to Color.Black,
)

suspend fun extractAccentFromUrl(context: Context, imageUrl: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request)
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    val palette = Palette.from(bitmap).generate()
    val rgb = palette.getDarkVibrantColor(
        palette.getMutedColor(palette.getDominantColor(0xFF1A1A2E.toInt())).toInt(),
    )
    return Color(rgb)
}

@Composable
fun rememberArtworkAccentColor(seed: String, imageUrl: String?): Color {
    val context = LocalContext.current
    var accent by remember(seed) { mutableStateOf(gradientAccentColor(seed)) }
    LaunchedEffect(seed, imageUrl) {
        accent = if (!imageUrl.isNullOrBlank()) {
            runCatching {
                withContext(Dispatchers.IO) { extractAccentFromUrl(context, imageUrl) }
            }.getOrNull() ?: gradientAccentColor(seed)
        } else {
            gradientAccentColor(seed)
        }
    }
    return accent
}

internal val ACCENT_GRADIENT_PALETTE = listOf(
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
