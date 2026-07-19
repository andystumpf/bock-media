package com.bockmedia.console.ui.listen

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.ui.theme.SpotifyElevated

@Composable
fun ListenAgentMicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = SpotifyElevated,
            tonalElevation = 0.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Listen agent",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
