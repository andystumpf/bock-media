package com.bockmedia.console.ui.driving

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.media.LocalPlaybackController

@Composable
fun DrivingModeScreen(repository: BockMediaRepository, onOpenNowPlaying: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { LocalPlaybackController(context) }
    val state by controller.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text("Driving Mode", style = MaterialTheme.typography.headlineLarge)
        Text(
            state.current?.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        state.current?.artist?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            IconButton(onClick = { controller.skip(context, forward = false) }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(44.dp))
            }
            IconButton(onClick = { controller.togglePlayPause(context) }) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = { controller.skip(context, forward = true) }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(44.dp))
            }
        }
        Button(onClick = onOpenNowPlaying) { Text("Open Now Playing") }
    }
}
