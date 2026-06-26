package com.bockmedia.console.ui.library

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import com.bockmedia.console.data.api.dto.displayName
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtistDetailRules
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.ArtBackdrop
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.formatTrackDuration
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class ArtistTrackRow(
    val title: String,
    val subtitle: String,
    val path: String?,
)

private enum class ArtistTrackTab(val label: String) {
    Popular("Popular"),
    Favorites("Highly rated"),
    Recent("Recently played"),
    MostPlayed("Most played"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenArtist: (String) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var studioAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var singleAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var trackPages by remember { mutableStateOf(List(ArtistTrackTab.entries.size) { emptyList<ArtistTrackRow>() }) }
    var similarArtists by remember { mutableStateOf<List<String>>(emptyList()) }
    var sonicArtists by remember { mutableStateOf<List<String>>(emptyList()) }
    var styles by remember { mutableStateOf<List<String>>(emptyList()) }
    var artistArtPath by remember { mutableStateOf<String?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showMixMuse by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    val playTarget = remember(artistName) { PlayTarget.Artist(artistName) }
    val shuffleTarget = remember(artistName) {
        PlayTarget.Radio(artistName, PlayTarget.RadioSeedKind.Artist, artistName)
    }
    val discoverySeed = remember(artistName) {
        DiscoverySeed(
            kind = DiscoverySeedKind.album,
            title = artistName,
            album = null,
            artist = artistName,
        )
    }
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = artistName,
        artPath = artistArtPath,
        artistName = artistName,
        variantKey = artistName,
    )
    val trackPagerState = rememberPagerState(initialPage = 0) { ArtistTrackTab.entries.size }

    ImmersiveDarkStatusBar()

    LaunchedEffect(artistName) {
        loading = true
        runCatching {
            coroutineScope {
                val albumsDeferred = async {
                    var resp = repository.albums(page = 1, search = "", artist = artistName, limit = 200, sort = "year")
                    if (resp.items.isEmpty()) {
                        resp = repository.albums(page = 1, search = artistName, limit = 200, sort = "year")
                    }
                    resp.items.filter { ArtistDetailRules.matchesArtist(it.artist ?: artistName, artistName) }
                }
                val artistMetaDeferred = async {
                    repository.artists(page = 1, search = artistName, limit = 20)
                        .items.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                }
                val favoritesDeferred = async { repository.ratedSongs() }
                val historyDeferred = async { repository.streamHistory(page = 1, limit = 100).items }
                val analyticsDeferred = async { repository.analytics() }
                val searchDeferred = async { repository.search(artistName, limit = 30, preview = 8) }

                val albums = albumsDeferred.await()
                val (studio, singles) = ArtistDetailRules.splitAlbums(albums)
                studioAlbums = studio
                singleAlbums = singles
                artistArtPath = artistMetaDeferred.await()?.artPath
                    ?: albums.firstNotNullOfOrNull { it.artPath }

                val songs = loadArtistSongs(repository, artistName, albums)
                val favorites = favoritesDeferred.await().map { row ->
                    FavoriteItem(
                        path = row.id,
                        track = row.title,
                        artist = row.artist,
                        album = row.album,
                    )
                }
                val history = historyDeferred.await()
                val analytics = analyticsDeferred.await()
                val search = searchDeferred.await()
                val artistAliases = (albums.mapNotNull { it.artist } + artistName).distinct()

                trackPages = buildTrackPages(artistName, artistAliases, songs, favorites, history, analytics?.topTracks.orEmpty())
                similarArtists = analytics?.topArtists.orEmpty()
                    .map { it.displayName() }
                    .filter { !it.equals(artistName, ignoreCase = true) }
                    .distinct()
                    .take(8)
                sonicArtists = search.similar
                    .mapNotNull { (it.name ?: it.title)?.trim() }
                    .filter { it.isNotBlank() && !it.equals(artistName, ignoreCase = true) }
                    .distinct()
                    .take(8)
                styles = songs.mapNotNull { it.genre?.trim() }
                    .filter { it.isNotBlank() }
                    .groupingBy { it.lowercase() }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key.replaceFirstChar { c -> c.uppercaseChar() } }
                    .distinct()
                    .take(12)
            }
        }
        loading = false
    }

    if (showDiscovery) {
        DiscoveryActionsDialog(
            seed = discoverySeed,
            onDismiss = { showDiscovery = false },
            onMixMuse = { showMixMuse = true; showDiscovery = false },
            onResonanceRadio = { scope.launch { repository.runResonanceRadio(context, discoverySeed) } },
            onResonanceMix = { scope.launch { repository.runResonanceMix(discoverySeed) { _, _ -> } } },
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
            ArtistInlineTopBar(title = artistName, onBack = onBack)
            if (loading && studioAlbums.isEmpty() && singleAlbums.isEmpty()) {
                LoadingBox(Modifier.weight(1f))
                return@Column
            }

            ArtistHeroHeader(
                artistName = artistName,
                artUrl = artUrl,
                onPlay = { onPlay(playTarget) },
                onShuffle = { onPlay(shuffleTarget) },
                onRadio = { showDiscovery = true },
                onMore = { showMore = true },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = Color.White.copy(alpha = 0.12f),
            )

            BockLazyColumn(Modifier.weight(1f)) {
                if (studioAlbums.isNotEmpty()) {
                    item(key = "albums-header") {
                        ArtistSectionHeader(
                            title = "ALBUMS",
                            subtitle = ArtistDetailRules.yearRange(studioAlbums),
                        )
                    }
                    items(studioAlbums.size, key = { "album-${studioAlbums[it].name}" }) { index ->
                        val album = studioAlbums[index]
                        ArtistAlbumRow(
                            repository = repository,
                            album = album,
                            onClick = { onOpenAlbum(album.name, album.artist ?: artistName) },
                        )
                    }
                }

                if (singleAlbums.isNotEmpty()) {
                    item(key = "singles-header") {
                        ArtistSectionHeader(
                            title = "SINGLES & EPs",
                            subtitle = ArtistDetailRules.yearRange(singleAlbums),
                        )
                    }
                    items(singleAlbums.size, key = { "single-${singleAlbums[it].name}" }) { index ->
                        val album = singleAlbums[index]
                        ArtistAlbumRow(
                            repository = repository,
                            album = album,
                            onClick = { onOpenAlbum(album.name, album.artist ?: artistName) },
                        )
                    }
                }

                item(key = "tracks-section") {
                    ArtistTrackPagerSection(
                        pagerState = trackPagerState,
                        pages = trackPages,
                        onTrackClick = { row ->
                            row.path?.let { path ->
                                onPlay(PlayTarget.Song(path, row.title))
                            }
                        },
                    )
                }

                if (similarArtists.isNotEmpty()) {
                    item(key = "similar-header") {
                        ArtistSectionHeader(title = "SIMILAR ARTISTS", trailing = "›")
                    }
                    items(similarArtists.size, key = { "similar-$it" }) { index ->
                        ArtistNameRow(
                            repository = repository,
                            name = similarArtists[index],
                            onClick = { onOpenArtist(similarArtists[index]) },
                        )
                    }
                }

                if (sonicArtists.isNotEmpty()) {
                    item(key = "sonic-header") {
                        ArtistSectionHeader(title = "SONICALLY SIMILAR ARTISTS", trailing = "›")
                    }
                    items(sonicArtists.size, key = { "sonic-$it" }) { index ->
                        ArtistNameRow(
                            repository = repository,
                            name = sonicArtists[index],
                            onClick = { onOpenArtist(sonicArtists[index]) },
                        )
                    }
                }

                if (styles.isNotEmpty()) {
                    item(key = "styles-header") {
                        ArtistSectionHeader(title = "ARTIST STYLES")
                    }
                    item(key = "styles-chips") {
                        ArtistStylesRow(styles = styles)
                    }
                }
            }
        }
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            title = { Text(artistName) },
            text = {
                Column {
                    TextButton(onClick = { showMore = false; showDiscovery = true }) {
                        Text("Resonance radio")
                    }
                    TextButton(onClick = { showMore = false; showMixMuse = true }) {
                        Text("Mix Muse playlist…")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMore = false }) { Text("Close") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistTrackPagerSection(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pages: List<List<ArtistTrackRow>>,
    onTrackClick: (ArtistTrackRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            divider = {},
            indicator = {},
        ) {
            ArtistTrackTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            tab.label,
                            color = if (pagerState.currentPage == index) Color.White else Color.White.copy(alpha = 0.55f),
                            fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val tracks = pages.getOrElse(page) { emptyList() }
            Column {
                if (tracks.isEmpty()) {
                    Text(
                        "Nothing here yet",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = Color.White.copy(alpha = 0.55f),
                    )
                } else {
                    tracks.forEach { row ->
                        ArtistTrackRowView(row = row, onClick = { onTrackClick(row) })
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            ArtistTrackTab.entries.forEachIndexed { index, _ ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (pagerState.currentPage == index) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == index) Color.White
                            else Color.White.copy(alpha = 0.35f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ArtistHeroHeader(
    artistName: String,
    artUrl: String?,
    onPlay: () -> Unit,
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
            title = artistName,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape),
            shape = CircleShape,
            fallbackFontSize = 28.sp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(52.dp))
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play artist", modifier = Modifier.size(28.dp))
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
private fun ArtistSectionHeader(title: String, subtitle: String? = null, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color.White,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.let {
            Text(it, color = Color.White.copy(alpha = 0.55f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ArtistAlbumRow(
    repository: BockMediaRepository,
    album: AlbumItem,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = album.name,
        artPath = album.artPath,
        albumName = album.name,
        albumArtist = album.artist,
        variantKey = "${album.name}|${album.artist}",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BockArtwork(
            model = artUrl,
            title = album.name,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp)),
            shape = RoundedCornerShape(4.dp),
            fallbackFontSize = 18.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(album.year?.toString(), "${album.tracks} tracks")
                .joinToString(" · ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ArtistTrackRowView(row: ArtistTrackRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ArtistNameRow(
    repository: BockMediaRepository,
    name: String,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = name,
        artistName = name,
        variantKey = name,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BockArtwork(
            model = artUrl,
            title = name,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            shape = CircleShape,
            fallbackFontSize = 16.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistStylesRow(styles: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        styles.take(6).forEach { style ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.12f),
            ) {
                Text(
                    style,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ArtistInlineTopBar(title: String, onBack: () -> Unit) {
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

private suspend fun loadArtistSongs(
    repository: BockMediaRepository,
    artistName: String,
    albums: List<AlbumItem>,
): List<com.bockmedia.console.data.api.dto.SongItem> {
    suspend fun byArtist(artist: String?) =
        repository.songs(page = 1, search = "", artist = artist, limit = 200).items

    var songs = byArtist(artistName)
    if (songs.isNotEmpty()) return songs

    songs = repository.songs(page = 1, search = artistName, limit = 200).items
        .filter { ArtistDetailRules.matchesArtist(it.artist, artistName) }
    if (songs.isNotEmpty()) return songs

    for (alias in albums.mapNotNull { it.artist }.distinct()) {
        if (alias.equals(artistName, ignoreCase = true)) continue
        songs = byArtist(alias)
        if (songs.isNotEmpty()) return songs
    }

    if (albums.isEmpty()) return emptyList()

    var fromAlbums = albums.flatMap { album ->
        repository.songs(page = 1, search = "", artist = album.artist, album = album.name, limit = 100).items
    }
    if (fromAlbums.isEmpty()) {
        fromAlbums = albums.flatMap { album ->
            repository.songs(page = 1, search = "", album = album.name, limit = 100).items
        }
    }
    val narrowed = fromAlbums.filter { song ->
        artistAliases(albums, artistName).any { ArtistDetailRules.matchesArtist(song.artist, it) }
    }
    return (if (narrowed.isNotEmpty()) narrowed else fromAlbums)
        .distinctBy { it.path?.takeIf { p -> p.isNotBlank() } ?: "${it.title}|${it.album}" }
}

private fun artistAliases(albums: List<AlbumItem>, artistName: String) =
    (albums.mapNotNull { it.artist } + artistName).distinct()

private fun matchesAnyArtist(artist: String?, names: List<String>): Boolean =
    names.any { ArtistDetailRules.matchesArtist(artist, it) }

private fun buildTrackPages(
    artistName: String,
    artistAliases: List<String>,
    songs: List<com.bockmedia.console.data.api.dto.SongItem>,
    favorites: List<FavoriteItem>,
    history: List<StreamHistoryItem>,
    topTracks: List<CountRow>,
): List<List<ArtistTrackRow>> {
    val popularFromAnalytics = topTracks
        .filter { matchesAnyArtist(it.artist, artistAliases) }
        .map { row ->
            ArtistTrackRow(
                title = row.displayName(),
                subtitle = "${row.count} plays",
                path = null,
            )
        }
    val popularFromLibrary = songs
        .distinctBy { it.title?.lowercase() }
        .map { song ->
            ArtistTrackRow(
                title = song.title ?: "—",
                subtitle = song.duration?.let { formatTrackDuration(it) } ?: song.album.orEmpty(),
                path = song.path,
            )
        }
    val popular = (popularFromAnalytics + popularFromLibrary)
        .distinctBy { it.title.lowercase() }
        .take(12)

    val favoriteRows = favorites
        .filter { matchesAnyArtist(it.artist, artistAliases) }
        .sortedByDescending { it.count }
        .map { fav ->
            ArtistTrackRow(
                title = fav.track ?: "—",
                subtitle = if (fav.count > 0) "${fav.count} ratings" else fav.album.orEmpty(),
                path = fav.path.takeIf { it.isNotBlank() },
            )
        }
        .take(12)

    val recentRows = history
        .filter { matchesAnyArtist(it.artist, artistAliases) }
        .distinctBy { it.filepath ?: "${it.track}|${it.album}" }
        .map { row ->
            ArtistTrackRow(
                title = row.track ?: "—",
                subtitle = formatRelativePlayedAt(row.timestamp ?: row.date) ?: row.album.orEmpty(),
                path = row.filepath,
            )
        }
        .take(12)

    val mostPlayed = topTracks
        .filter { matchesAnyArtist(it.artist, artistAliases) }
        .sortedByDescending { it.count }
        .map { row ->
            ArtistTrackRow(
                title = row.displayName(),
                subtitle = "${row.count} plays",
                path = null,
            )
        }
        .take(12)

    return listOf(popular, favoriteRows, recentRows, mostPlayed)
}

private fun formatRelativePlayedAt(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val instant = raw.toDoubleOrNull()?.let { sec ->
        Instant.ofEpochSecond(sec.toLong())
    } ?: runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetParse(raw) }.getOrNull()
        ?: return null
    val days = Duration.between(instant, Instant.now()).toDays()
    return when {
        days < 1 -> "Today"
        days == 1L -> "1 day ago"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}

private fun OffsetParse(raw: String): Instant? {
    val normalized = raw.trim().replace(' ', 'T')
    return runCatching {
        LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
    }.getOrNull()
}
