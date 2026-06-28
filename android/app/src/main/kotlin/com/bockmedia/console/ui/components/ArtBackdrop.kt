package com.bockmedia.console.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

/** Plexamp-style blurred album-art background with dark gradient overlay. */
@Composable
fun ArtBackdrop(artUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        SubcomposeAsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(56.dp) else Modifier),
            contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) },
            error = { Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) },
            success = { SubcomposeAsyncImageContent() },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.2f to Color.Black.copy(alpha = 0.55f),
                        0.55f to Color.Black.copy(alpha = 0.42f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ),
        )
    }
}
