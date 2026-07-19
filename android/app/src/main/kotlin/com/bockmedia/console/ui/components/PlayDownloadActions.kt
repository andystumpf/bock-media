package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.ui.downloads.LocalVisibleDownloadStatuses
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.theme.BockGreen

private val OnGreen = Color(0xFF0F1419)

@Composable
fun PlayDownloadActions(
    playTarget: PlayTarget,
    remoteOk: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    showDownload: Boolean = true,
    showPlay: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        leading?.invoke()
        if (showDownload) DownloadStatusControl(playTarget)
        if (showPlay) CircularPlayButton(onClick = onPlay)
    }
}

/** Play + download overlays for square artwork tiles (Home, Library grid). */
@Composable
fun BoxScope.ArtworkTileOverlayActions(
    playTarget: PlayTarget,
    onPlay: () -> Unit,
    showDownload: Boolean = true,
    showPlay: Boolean = true,
) {
    if (showDownload) {
        DownloadStatusControl(
            playTarget = playTarget,
            onArtwork = true,
            modifier = Modifier
                .zIndex(2f)
                .align(Alignment.TopEnd)
                .padding(4.dp),
        )
    }
    if (showPlay) {
        CircularPlayButton(
            onClick = onPlay,
            size = 48.dp,
            elevated = true,
            modifier = Modifier
                .zIndex(2f)
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )
    }
}

/**
 * Offline state for [playTarget]: tap to download, a live progress ring while the
 * collection is syncing, tap the green check to remove offline, or a download icon
 * when not downloaded. Set [onArtwork] when overlaying on cover art so it stays
 * legible on a busy image.
 */
@Composable
fun DownloadStatusControl(
    playTarget: PlayTarget,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
) {
    val context = LocalContext.current
    val status = LocalVisibleDownloadStatuses.current[playTarget.downloadId()]
    val box = modifier.size(40.dp)
    val inner = if (onArtwork) {
        Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
    } else {
        Modifier.size(40.dp)
    }
    val idleTint = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    when (status?.state) {
        DownloadState.Downloading -> Box(box, contentAlignment = Alignment.Center) {
            Box(inner, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { status.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = BockGreen,
                    trackColor = (if (onArtwork) Color.White else BockGreen).copy(alpha = 0.25f),
                )
            }
        }
        DownloadState.Idle -> Box(box, contentAlignment = Alignment.Center) {
            Box(inner, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = (if (onArtwork) Color.White else BockGreen).copy(alpha = 0.55f),
                    trackColor = (if (onArtwork) Color.White else BockGreen).copy(alpha = 0.25f),
                )
            }
        }
        DownloadState.Complete -> {
            Box(
                box
                    .clip(CircleShape)
                    .clickable {
                        OfflineDownloadManager.deleteCollection(context, playTarget.downloadId())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(inner, contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.DownloadDone,
                        contentDescription = "Remove offline download",
                        tint = BockGreen,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        else -> Box(
            box.clip(CircleShape).clickable { OfflineDownloadManager.download(context, playTarget) },
            contentAlignment = Alignment.Center,
        ) {
            Box(inner, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = idleTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** Prominent filled-green circular play button — the primary action for a collection. */
@Composable
fun CircularPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    elevated: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .then(if (elevated) Modifier.shadow(6.dp, CircleShape) else Modifier)
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) BockGreen else BockGreen.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = OnGreen,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
