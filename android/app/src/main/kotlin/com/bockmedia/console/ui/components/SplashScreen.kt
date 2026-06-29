package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bockmedia.console.ui.testing.BockTestTags

@Composable
fun SplashScreen(message: String = "Connecting…") {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(BockTestTags.SPLASH),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BockProgressIndicator(size = 48.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
