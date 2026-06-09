package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.OfflineDownloadManager

private val SpotifyGreen = Color(0xFF1DB954)

@Composable
fun PlayDownloadActions(
    playTarget: PlayTarget,
    remoteOk: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    showDownload: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        if (showDownload) {
            IconButton(onClick = { OfflineDownloadManager.download(context, playTarget) }) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = SpotifyGreen)
            }
        }
        if (remoteOk) PlayButton(onClick = onPlay)
    }
}
