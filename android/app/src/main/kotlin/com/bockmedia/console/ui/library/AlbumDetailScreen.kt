package com.bockmedia.console.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.SongItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.GraphicEq
import com.bockmedia.console.ui.components.AddToPlaylistSheet
import com.bockmedia.console.ui.components.AddToRoomSheet
import com.bockmedia.console.ui.components.ArtBackdrop
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PlexampEntitySheet
import com.bockmedia.console.ui.components.PlexampSheetAction
import com.bockmedia.console.ui.components.RatingKind
import com.bockmedia.console.ui.components.RatingTarget
import com.bockmedia.console.ui.components.albumRatingId
import com.bockmedia.console.ui.components.formatAlbumSummary
import com.bockmedia.console.ui.components.formatTrackDuration
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import com.bockmedia.console.ui.theme.BockGold
import android.app.Activity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

private data class DiscGroup(val number: Int, val tracks: List<SongItem>)

@Composable
fun AlbumDetailScreen(
    albumName: String,
    artistFilter: String?,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var totalTracks by remember { mutableIntStateOf(0) }
    var albumMeta by remember { mutableStateOf<AlbumItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showMixMuse by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf<Pair<String, String>?>(null) }
    var trackMenu by remember { mutableStateOf<SongItem?>(null) }
    var addToRoom by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var hotTrackTitles by remember { mutableStateOf<Set<String>>(emptySet()) }

    val playTarget = remember(albumName, artistFilter) {
        PlayTarget.Album(albumName, artistFilter)
    }
    val discoverySeed = remember(albumName, artistFilter) {
        DiscoverySeed(
            kind = DiscoverySeedKind.album,
            title = albumName,
            album = albumName,
            artist = artistFilter,
        )
    }
    val artistName = artistFilter ?: tracks.firstOrNull()?.artist ?: albumMeta?.artist
    val year = albumMeta?.year ?: tracks.firstNotNullOfOrNull { it.year }
    val artPath = albumMeta?.artPath ?: tracks.firstNotNullOfOrNull { it.path }
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = albumName,
        artPath = artPath,
        albumName = albumName,
        albumArtist = artistName,
        variantKey = "$albumName|$artistName",
    )
    val totalSeconds = tracks.sumOf { it.duration ?: 0 }
    val discGroups = remember(tracks) { groupTracksByDisc(tracks) }

    ImmersiveDarkStatusBar()

    LaunchedEffect(albumName, artistFilter) {
        loading = true
        runCatching {
            suspend fun loadSongs(artist: String?) = repository.songs(
                page = 1,
                search = "",
                artist = artist,
                album = albumName,
                limit = 500,
            )
            // Artist on library rows can differ from tags on files (e.g. Yusuf vs Cat Stevens).
            var songsResp = if (!artistFilter.isNullOrBlank()) loadSongs(artistFilter) else loadSongs(null)
            if (songsResp.items.isEmpty()) {
                songsResp = loadSongs(null)
            }
            var loaded = sortAlbumTracks(songsResp.items)
            if (!artistFilter.isNullOrBlank()) {
                val narrowed = loaded.filter { it.artist.equals(artistFilter, ignoreCase = true) }
                if (narrowed.isNotEmpty()) loaded = sortAlbumTracks(narrowed)
            }
            tracks = loaded
            totalTracks = songsResp.total.takeIf { it > 0 } ?: loaded.size
            albumMeta = repository.albums(
                page = 1,
                search = albumName,
                artist = artistFilter,
                limit = 20,
            ).items.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
                ?: repository.albums(page = 1, search = albumName, limit = 20)
                    .items.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        runCatching {
            hotTrackTitles = repository.analytics()?.topTracks.orEmpty()
                .mapNotNull { (it.name ?: it.label)?.trim()?.lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    addToPlaylist?.let { (path, title) ->
        AddToPlaylistSheet(
            repository = repository,
            trackPath = path,
            trackTitle = title,
            onDismiss = { addToPlaylist = null },
            onAdded = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
    }
    addToRoom?.let { (path, title, artist) ->
        AddToRoomSheet(
            repository = repository,
            path = path,
            track = title,
            artist = artist,
            remoteOk = remoteOk,
            onDismiss = { addToRoom = null },
            onSuccess = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg); addToRoom = null } },
            onError = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
    }
    if (showDiscovery) {
        DiscoveryActionsDialog(
            seed = discoverySeed,
            onDismiss = { showDiscovery = false },
            onMixMuse = { showMixMuse = true; showDiscovery = false },
            onResonanceRadio = {
                scope.launch { repository.runResonanceRadio(context, discoverySeed) }
            },
            onResonanceMix = {
                scope.launch { repository.runResonanceMix(discoverySeed) { _, _ -> } }
            },
            onAcquireIdeas = { showDiscovery = false },
        )
    }
    if (showMixMuse) {
        MixMuseDialog(
            repository = repository,
            seed = discoverySeed,
            onDismiss = { showMixMuse = false },
            onPlaylistCreated = { _, _ -> },
        )
    }

    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(artUrl = artUrl)
        Column(Modifier.fillMaxSize()) {
            AlbumInlineTopBar(title = albumName, onBack = onBack)
            if (loading && tracks.isEmpty()) {
                LoadingBox(Modifier.weight(1f))
                return@Column
            }

            AlbumHeroHeader(
                artistName = artistName,
                year = year,
                artUrl = artUrl,
                albumName = albumName,
                onPlayAlbum = { onPlay(playTarget) },
                onShuffle = { onPlay(PlayTarget.Album(albumName, artistFilter, shuffle = true)) },
                onRadio = { showDiscovery = true },
                onMore = { showMore = true },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = Color.White.copy(alpha = 0.12f),
            )

            BockLazyColumn(Modifier.weight(1f)) {
                discGroups.forEach { disc ->
                    val summaryTracks = if (discGroups.size == 1) totalTracks else disc.tracks.size
                    val summarySeconds = if (discGroups.size == 1) totalSeconds else disc.tracks.sumOf { it.duration ?: 0 }
                    item(key = "disc-${disc.number}") {
                        DiscHeaderRow(
                            discNumber = disc.number,
                            trackCount = summaryTracks,
                            totalSeconds = summarySeconds,
                            onPlayDisc = { onPlay(playTarget) },
                        )
                    }
                    itemsIndexed(disc.tracks, key = { idx, track ->
                        track.path ?: "${disc.number}-$idx-${track.title}"
                    }) { index, track ->
                        AlbumTrackRow(
                            track = track,
                            displayNumber = track.trackNumber ?: (index + 1),
                            isHot = track.title?.trim()?.lowercase() in hotTrackTitles,
                            onClick = {
                                track.path?.let { path ->
                                    onPlay(PlayTarget.Song(path, track.title ?: ""))
                                }
                            },
                            onMenu = { trackMenu = track },
                        )
                    }
                }
                if (tracks.isEmpty() && !loading) {
                    item {
                        Text(
                            "No tracks found",
                            modifier = Modifier.padding(24.dp),
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }

    trackMenu?.let { track ->
        val path = track.path
        if (path != null) {
            PlexampEntitySheet(
                title = track.title ?: "Track",
                rating = RatingTarget(
                    kind = RatingKind.Song,
                    id = path,
                    title = track.title ?: "",
                    artist = track.artist,
                    album = track.album,
                ),
                repository = repository,
                actions = buildList {
                    add(PlexampSheetAction("Play", Icons.Default.PlayArrow, onClick = {
                        onPlay(PlayTarget.Song(path, track.title ?: ""))
                    }))
                    add(PlexampSheetAction("Add to playlist", Icons.Default.PlaylistAdd, onClick = {
                        addToPlaylist = path to (track.title ?: "Track")
                    }))
                    if (remoteOk) {
                        add(PlexampSheetAction("Add to room", Icons.Default.Add, onClick = {
                            addToRoom = Triple(path, track.title ?: "Track", track.artist)
                            trackMenu = null
                        }))
                    }
                },
                onDismiss = { trackMenu = null },
            )
        }
    }

    if (showMore) {
        PlexampEntitySheet(
            title = albumName,
            rating = RatingTarget(
                kind = RatingKind.Album,
                id = albumRatingId(albumName, artistFilter),
                title = albumName,
                artist = artistFilter,
                album = albumName,
            ),
            repository = repository,
            actions = listOf(
                PlexampSheetAction("Resonance radio", Icons.Outlined.GraphicEq, onClick = { showDiscovery = true }),
                PlexampSheetAction("Mix Muse playlist…", Icons.Default.AutoAwesome, onClick = { showMixMuse = true }),
            ),
            onDismiss = { showMore = false },
        )
    }
}

@Composable
private fun AlbumHeroHeader(
    artistName: String?,
    year: Int?,
    artUrl: String?,
    albumName: String,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BockArtwork(
            model = artUrl,
            title = albumName,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(6.dp)),
            shape = RoundedCornerShape(6.dp),
            fallbackFontSize = 28.sp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            artistName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            year?.let {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            FilledIconButton(
                onClick = onPlayAlbum,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play album", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = Color.White)
                }
                IconButton(onClick = onRadio) {
                    Icon(Icons.Outlined.GraphicEq, contentDescription = "Radio", tint = Color.White)
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DiscHeaderRow(
    discNumber: Int,
    trackCount: Int,
    totalSeconds: Int,
    onPlayDisc: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "DISC $discNumber",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = Color.White,
        )
        IconButton(onClick = onPlayDisc, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play disc",
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            formatAlbumSummary(trackCount, totalSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AlbumTrackRow(
    track: SongItem,
    displayNumber: Int,
    isHot: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isHot) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = BockGold,
                modifier = Modifier
                    .padding(end = 4.dp, top = 2.dp)
                    .size(18.dp),
            )
        } else {
            Spacer(Modifier.width(22.dp))
        }
        Text(
            displayNumber.toString(),
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            track.duration?.let { dur ->
                Text(
                    formatTrackDuration(dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.MoreVert, contentDescription = "Track options", tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

private fun sortAlbumTracks(items: List<SongItem>): List<SongItem> =
    items
        .distinctBy { it.path?.takeIf { p -> p.isNotBlank() } ?: "${it.discNumber}|${it.trackNumber}|${it.title}" }
        .sortedWith(
            compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.orEmpty() }),
        )

private fun groupTracksByDisc(tracks: List<SongItem>): List<DiscGroup> {
    if (tracks.isEmpty()) return emptyList()
    val grouped = tracks.groupBy { it.discNumber ?: 1 }
    return grouped.keys.sorted().map { num -> DiscGroup(num, grouped[num].orEmpty()) }
}

/** Light status-bar icons on dark album backdrop; restored when leaving the screen. */
@Composable
private fun ImmersiveDarkStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val wasLight = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = wasLight }
    }
}

@Composable
private fun AlbumInlineTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(48.dp))
    }
}
