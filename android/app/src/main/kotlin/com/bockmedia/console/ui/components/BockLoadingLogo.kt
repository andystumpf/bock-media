package com.bockmedia.console.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bockmedia.console.R

/** Emblem center in `bock_logo_*.png` (192×192 launcher art). */
private const val CAP_PIVOT_X = 0.5f
private const val CAP_PIVOT_Y = 72f / 192f

@Composable
fun BockLoadingLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    animating: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bockCap")
    val animatedRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
        ),
        label = "capRotation",
    )
    val rotation = if (animating) animatedRotation else 0f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.bock_logo_base),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Image(
            painter = painterResource(R.drawable.bock_logo_cap),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    transformOrigin = TransformOrigin(CAP_PIVOT_X, CAP_PIVOT_Y)
                },
            contentScale = ContentScale.Fit,
        )
    }
}
