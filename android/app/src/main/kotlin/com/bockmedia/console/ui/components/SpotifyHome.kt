package com.bockmedia.console.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.HomeArtworkResolver
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.HomeSection
import com.bockmedia.console.domain.model.HomeSectionKind
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.ui.theme.*

private val PillShape = RoundedCornerShape(50)
private val ArtShape = RoundedCornerShape(4.dp)
private val TileSize = 148.dp

@Composable
fun HomeHeader(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onAccountNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(HomeGradientTop, HomeGradientBottom, HomeGradientBottom),
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
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreeting(modifier = Modifier.weight(1f))
                AccountMenuButton(onAccountNavigate)
            }
            HomePillFilters(
                selected = selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
fun HomePillFilters(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeFilter.entries.toList()) { filter ->
            val isActive = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                shape = PillShape,
                color = if (isActive) HomePillActive else HomePillInactive,
                shadowElevation = if (isActive) 1.dp else 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    filter.label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun HomeShortcutGrid(
    cards: List<HomeCard>,
    repository: BockMediaRepository,
    artworkEpoch: Int,
    onPlay: (HomeCard) -> Unit,
    onLongPress: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    val shortcuts = cards.take(6)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shortcuts.chunked(2).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowCards.forEach { card ->
                    HomeShortcutTile(
                        card = card,
                        repository = repository,
                        artworkEpoch = artworkEpoch,
                        onClick = { onPlay(card) },
                        onLongClick = { onLongPress(card) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowCards.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    card: HomeCard,
    repository: BockMediaRepository,
    artworkEpoch: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SpotifyElevated)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artworkEpoch = artworkEpoch,
            modifier = Modifier
                .size(56.dp)
                .clip(ArtShape),
        )
        Text(
            card.title,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
    }
}

@Composable
fun SpotifyHomeSection(
    section: HomeSection,
    repository: BockMediaRepository,
    artworkEpoch: Int,
    onPlay: (HomeCard) -> Unit,
    onLongPress: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
    onShowAll: ((HomeSection) -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onShowAll != null && section.cards.size > 4) {
                TextButton(
                    onClick = { onShowAll(section) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        "Show all",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = BockMuted,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = BockMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(section.cards, key = { it.id }) { card ->
                when (section.kind) {
                    HomeSectionKind.TopMixes, HomeSectionKind.DailyMixes ->
                        GenreMixTile(
                            card = card,
                            repository = repository,
                            artworkEpoch = artworkEpoch,
                            onPlay = onPlay,
                            onLongPress = { onLongPress(card) },
                        )
                    else -> PlaylistArtTile(
                        card = card,
                        repository = repository,
                        artworkEpoch = artworkEpoch,
                        onPlay = onPlay,
                        onLongPress = { onLongPress(card) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCardActionSheet(
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
        containerColor = SpotifyElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            card.subtitle?.let {
                Text(it, color = BockMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(
                onClick = onPlay,
                shape = RoundedCornerShape(4.dp),
                color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BockGreen)
                    Spacer(Modifier.width(12.dp))
                    Text("Play", color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (downloadState != DownloadState.Downloading) {
                Surface(
                    onClick = onDownload,
                    shape = RoundedCornerShape(4.dp),
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = BockGreen)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (downloadState == DownloadState.Complete) "Re-download for offline" else "Download for offline",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                Text("Download in progress…", color = BockMuted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionShowAllSheet(
    section: HomeSection,
    onDismiss: () -> Unit,
    onPlay: (HomeCard) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifyElevated,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                section.cards.forEach { card ->
                    Surface(
                        onClick = { onPlay(card) },
                        color = SpotifyElevated,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                card.title,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            card.subtitle?.let {
                                Text(it, color = BockMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}

@Composable
private fun HomeCardArt(
    card: HomeCard,
    repository: BockMediaRepository,
    artworkEpoch: Int,
    modifier: Modifier = Modifier,
) {
    val baseUrl = remember(repository) { repository.peekBaseUrl() }
    var artUrl by remember(card.id, artworkEpoch, baseUrl) {
        mutableStateOf(HomeArtworkResolver.peekUrl(baseUrl, card))
    }
    LaunchedEffect(card.id, artworkEpoch, baseUrl) {
        artUrl = HomeArtworkResolver.peekUrl(baseUrl, card)
            ?: HomeArtworkResolver.resolveUrl(repository, card)
    }
    BockArtwork(
        model = artUrl,
        title = card.title,
        modifier = modifier,
        shape = ArtShape,
        fallbackFontSize = 18.sp,
        crossfadeMs = 0,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistArtTile(
    card: HomeCard,
    repository: BockMediaRepository,
    artworkEpoch: Int,
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(TileSize)
            .combinedClickable(
                onClick = { onPlay(card) },
                onLongClick = onLongPress,
            ),
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artworkEpoch = artworkEpoch,
            modifier = Modifier
                .size(TileSize)
                .clip(ArtShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        card.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = BockMuted,
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
    artworkEpoch: Int,
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
) {
    val accent = mixAccentColor(card.id)
    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(ArtShape)
            .combinedClickable(
                onClick = { onPlay(card) },
                onLongClick = onLongPress,
            ),
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artworkEpoch = artworkEpoch,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            accent.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
