package com.bockmedia.console.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.HomeSection
import com.bockmedia.console.domain.model.HomeSectionKind
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.theme.*

private val PillShape = RoundedCornerShape(50)
private val SpotifySheetBg = Color(0xFF282828)

@Composable
fun HomeHeader(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(HomeGradientTop, HomeGradientBottom),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreeting(modifier = Modifier.weight(1f))
                AccountMenuButton(onAccountNavigate)
            }
            Spacer(Modifier.height(4.dp))
            HomePillFilters(
                selected = selected,
                onSelect = onSelect,
                onOpenDownloads = onOpenDownloads,
            )
        }
    }
}

@Composable
fun HomePillFilters(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val inactive = HomePillInactive
    val active = HomePillActive
    val inactiveText = MaterialTheme.colorScheme.onSurface
    val activeText = Color(0xFF0F1419)
    val downloadStatuses by OfflineDownloadManager.statuses.collectAsState()
    val showDownloadsPill = downloadStatuses.values.any {
        it.state == DownloadState.Downloading || it.state == DownloadState.Failed
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (showDownloadsPill) {
            item(key = "downloads-pill") {
                HomeDownloadsPillRow(onOpenDownloads = onOpenDownloads)
            }
        }
        items(HomeFilter.entries.toList()) { filter ->
            val isActive = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                shape = PillShape,
                color = if (isActive) active else inactive,
                shadowElevation = if (isActive) 1.dp else 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    filter.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) activeText else inactiveText,
                )
            }
        }
    }
}

@Composable
fun SpotifyHomeSection(
    section: HomeSection,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    onDownload: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
    compactTop: Boolean = false,
    artLoadKey: Any = Unit,
) {
    var actionCard by remember { mutableStateOf<HomeCard?>(null) }
    val statuses by OfflineDownloadManager.statuses.collectAsState()

    actionCard?.let { card ->
        HomeCardActionSheet(
            card = card,
            downloadState = statuses[card.playTarget.downloadId()]?.state,
            onDismiss = { actionCard = null },
            onPlay = {
                actionCard = null
                onPlay(card)
            },
            onDownload = {
                actionCard = null
                onDownload(card)
            },
        )
    }

    Column(modifier = modifier.padding(top = if (compactTop) 6.dp else 12.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(section.cards, key = { "${artLoadKey}-${it.id}" }) { card ->
                val downloadStatus = statuses[card.playTarget.downloadId()]
                val downloaded = downloadStatus?.state == DownloadState.Complete
                val downloading = downloadStatus?.state == DownloadState.Downloading
                val downloadProgress = downloadStatus?.progress ?: 0f
                when (section.kind) {
                    HomeSectionKind.TopMixes, HomeSectionKind.DailyMixes ->
                        GenreMixTile(
                            card, repository, onPlay,
                            onLongPress = { actionCard = card },
                            onDownload = { onDownload(card) },
                            artLoadKey, downloaded, downloading, downloadProgress,
                        )
                    else -> PlaylistArtTile(
                        card, repository, onPlay,
                        onLongPress = { actionCard = card },
                        onDownload = { onDownload(card) },
                        artLoadKey, downloaded, downloading, downloadProgress,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCardActionSheet(
    card: HomeCard,
    downloadState: DownloadState?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifySheetBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            card.subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Surface(onClick = onPlay, shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = BockGreen)
                    Spacer(Modifier.width(12.dp))
                    Text("Play", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (downloadState != DownloadState.Downloading) {
                Surface(onClick = onDownload, shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (downloadState == DownloadState.Complete) Icons.Default.DownloadDone else Icons.Default.Download,
                            null,
                            tint = BockGreen,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (downloadState == DownloadState.Complete) "Re-download for offline" else "Download for offline",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                Text("Download in progress…", color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun HomeCardArt(
    card: HomeCard,
    repository: BockMediaRepository,
    artLoadKey: Any,
    modifier: Modifier = Modifier,
) {
    val artUrl by produceState<String?>(initialValue = null, card.id, card.artPath, card.playlistId, artLoadKey) {
        value = repository.resolveHomeCardArtUrl(card.id, card.artPath, card.playlistId, card.playTarget)
    }
    BockArtwork(
        model = artUrl,
        title = card.title,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        fallbackFontSize = 18.sp,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistArtTile(
    card: HomeCard,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    artLoadKey: Any,
    downloaded: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .combinedClickable(
                onClick = { onPlay(card) },
                onLongClick = onLongPress,
            ),
    ) {
        Box {
            HomeCardArt(
                card = card,
                repository = repository,
                artLoadKey = artLoadKey,
                modifier = Modifier
                    .size(124.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (downloading) {
                DownloadProgressOverlay(downloadProgress, Modifier.matchParentSize())
            } else if (downloaded) {
                Icon(
                    Icons.Default.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = BockGreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
            if (!downloading) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp),
                ) {
                    Icon(
                        if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        contentDescription = "Download",
                        tint = BockGreen,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
        )
        card.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenreMixTile(
    card: HomeCard,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    artLoadKey: Any,
    downloaded: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
) {
    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 140.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { onPlay(card) },
                onLongClick = onLongPress,
            ),
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artLoadKey = artLoadKey,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.72f),
                        ),
                        startY = 40f,
                    ),
                ),
        )
        if (downloading) {
            DownloadProgressOverlay(downloadProgress, Modifier.matchParentSize())
        } else if (downloaded) {
            Icon(
                Icons.Default.DownloadDone,
                contentDescription = "Downloaded",
                tint = BockGreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
        if (!downloading) {
            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(36.dp),
            ) {
                Icon(
                    if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = "Download",
                    tint = BockGreen,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressOverlay(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(36.dp),
                color = BockGreen,
                trackColor = Color.White.copy(alpha = 0.25f),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp),
            color = BockGreen,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
fun HomeGreeting(modifier: Modifier = Modifier) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Text(
        greeting,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
