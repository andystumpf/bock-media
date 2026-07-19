package com.bockmedia.console.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkResolver
import com.bockmedia.console.domain.model.HomeFeedRules
import com.bockmedia.console.domain.model.HomeCardBrowse
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.linkedPlaylistId
import com.bockmedia.console.domain.model.showsHomeDownloadOverlay
import com.bockmedia.console.domain.model.showsHomePlayOverlay
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.HomeSection
import com.bockmedia.console.domain.model.HomeSectionKind
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.ui.listen.ListenAgentMicButton
import com.bockmedia.console.ui.theme.*

private val PillShape = RoundedCornerShape(50)
private val ArtShape = RoundedCornerShape(4.dp)
private val TileSize = 148.dp

private fun openHomeCard(
    card: HomeCard,
    onBrowse: (HomeCard) -> Unit,
) {
    if (HomeCardBrowse.destination(card) != null) {
        onBrowse(card)
    }
}

@Composable
fun HomeHeader(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenListenAgent: () -> Unit = {},
    profileFirstName: String? = null,
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
                .padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreeting(profileFirstName = profileFirstName, modifier = Modifier.weight(1f))
                ListenAgentMicButton(onClick = onOpenListenAgent)
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
                        onPlay = onPlay,
                        onLongPress = { onLongPress(card) },
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
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SpotifyElevated)
            .combinedClickable(
                onClick = { onPlay(card) },
                onLongClick = onLongPress,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
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
    remoteOk: Boolean,
    onPlay: (HomeCard) -> Unit,
    onLongPress: (HomeCard) -> Unit,
    onBrowse: (HomeCard) -> Unit,
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
        // LazyRow so only visible tiles compose — an eager Row builds every card in
        // the section the moment it scrolls in, freezing the vertical fling.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(section.cards, key = { it.id }, contentType = { "home_tile" }) { card ->
                HomeCollectionTile(
                    card = card,
                    sectionKind = section.kind,
                    sectionId = section.id,
                    repository = repository,
                    remoteOk = remoteOk || card.kind == HomeSectionKind.Offline,
                    onBrowse = onBrowse,
                    onPlay = onPlay,
                    onLongPress = { onLongPress(card) },
                    modifier = Modifier.width(TileSize),
                )
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
            if (downloadState != DownloadState.Downloading && downloadState != DownloadState.Idle) {
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
            } else if (downloadState == DownloadState.Idle) {
                Text("Queued for download…", color = BockMuted, modifier = Modifier.padding(16.dp))
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
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onDismiss: () -> Unit,
    onPlay: (HomeCard) -> Unit,
    onBrowse: (HomeCard) -> Unit,
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
                    ListItem(
                        modifier = Modifier.clickable {
                            openHomeCard(card, onBrowse)
                        },
                        leadingContent = {
                            HomeCardArt(
                                card = card,
                                repository = repository,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(ArtShape),
                            )
                        },
                        headlineContent = {
                            Text(
                                card.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            card.subtitle?.let {
                                Text(
                                    it,
                                    color = BockMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingContent = {
                            PlayDownloadActions(
                                playTarget = card.playTarget,
                                remoteOk = remoteOk || card.kind == HomeSectionKind.Offline,
                                onPlay = { onPlay(card) },
                                showDownload = card.showsHomeDownloadOverlay(section.kind, section.id),
                                showPlay = card.showsHomePlayOverlay(section.kind, section.id),
                            )
                        },
                    )
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
    modifier: Modifier = Modifier,
) {
    val target = card.playTarget
    val baseUrl = remember(repository) { repository.peekBaseUrl() }
    HomeArtworkResolver.peekUrl(baseUrl, card)?.let { cachedUrl ->
        BockArtwork(
            model = cachedUrl,
            title = card.title,
            modifier = modifier,
            shape = ArtShape,
            fallbackFontSize = 18.sp,
            crossfadeMs = 0,
        )
        return
    }
    val playlistId = card.linkedPlaylistId() ?: (target as? PlayTarget.Playlist)?.id
    if (!playlistId.isNullOrBlank()) {
        PlaylistTileArt(
            repository = repository,
            playlistId = playlistId,
            title = card.title,
            artPath = card.artPath,
            modifier = modifier,
            shape = ArtShape,
            fallbackFontSize = 18.sp,
            sizePx = TILE_ART_SIZE_PX,
        )
        return
    }
    val genreName = HomeFeedRules.mixGenreLabel(card.title)
        ?: (target as? PlayTarget.Radio)?.takeIf { it.seedKind == PlayTarget.RadioSeedKind.Genre }?.let {
            HomeFeedRules.mixGenreLabel(it.displayTitle) ?: HomeFeedRules.genreRadioLabel(it.displayTitle)
        }
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = card.title,
        artPath = card.artPath,
        playlistId = card.linkedPlaylistId(),
        variantKey = card.id,
        cardId = card.id,
        playTarget = target,
        genreName = genreName,
        artistName = when (target) {
            is PlayTarget.Artist -> target.name
            is PlayTarget.Radio -> if (target.seedKind == PlayTarget.RadioSeedKind.Artist) target.name else null
            else -> null
        },
        albumName = (target as? PlayTarget.Album)?.name,
        albumArtist = (target as? PlayTarget.Album)?.artist,
        sizePx = TILE_ART_SIZE_PX,
    )
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
private fun HomeCollectionTile(
    card: HomeCard,
    sectionKind: HomeSectionKind,
    sectionId: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onBrowse: (HomeCard) -> Unit,
    onPlay: (HomeCard) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(8.dp)
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(tileShape)
                    .combinedClickable(
                        onClick = { openHomeCard(card, onBrowse) },
                        onLongClick = onLongPress,
                    ),
            ) {
                HomeCardArt(
                    card = card,
                    repository = repository,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ArtworkTileOverlayActions(
                playTarget = card.playTarget,
                showDownload = card.showsHomeDownloadOverlay(sectionKind, sectionId),
                showPlay = card.showsHomePlayOverlay(sectionKind, sectionId),
                onPlay = { onPlay(card) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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

@Composable
fun HomeGreeting(
    profileFirstName: String? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        homeGreetingText(profileFirstName),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.testTag(BockTestTags.HOME_GREETING),
    )
}

/** Time-of-day greeting, optionally with the active profile's first name ("Good morning Andy"). */
fun homeGreetingText(profileFirstName: String? = null): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val period = when (hour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val first = profileFirstName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    return if (first != null) "$period $first" else period
}
