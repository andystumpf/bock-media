package com.bockmedia.console.ui.library

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.isServerConnectionError
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.ArtistDetailTrack
import com.bockmedia.console.data.api.dto.MusicVideoRelatedItem
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.api.dto.RatingItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtistAlbumSort
import com.bockmedia.console.domain.model.ArtistDetailRules
import com.bockmedia.console.domain.model.ArtistDetailSessionCache
import com.bockmedia.console.domain.model.ArtistDiscographyNavCache
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.AddToPlaylistSheet
import com.bockmedia.console.ui.components.AddToRoomSheet
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PlayDownloadActions
import com.bockmedia.console.ui.components.PlexampEntitySheet
import com.bockmedia.console.ui.components.PlexampSheetAction
import com.bockmedia.console.ui.components.RatingKind
import com.bockmedia.console.ui.components.RatingTarget
import com.bockmedia.console.ui.components.albumRatingId
import com.bockmedia.console.ui.components.formatTrackDuration
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.components.rememberArtworkAccentColor
import com.bockmedia.console.ui.components.heroGradientBrush
import com.bockmedia.console.ui.components.DetailShareSheet
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.playDiscoveryTracksLocally
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.ui.listen.ListenAgentMicButton
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ArtistPopularTrack(
    val rank: Int,
    val title: String,
    val path: String?,
    val artPath: String?,
    val album: String?,
    val artist: String?,
    val playCount: Int?,
    val durationSeconds: Int?,
    val liked: Boolean = false,
    val rating: Int = 0,
)

private const val POPULAR_PREVIEW_COUNT = 5

@Composable
fun ArtistDetailScreen(
    artistName: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenGenre: (String) -> Unit = {},
    onOpenListenAgent: (String) -> Unit = {},
    onOpenDiscography: () -> Unit = {},
    onLocalPlayStarted: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var studioAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var singleAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var popularTracks by remember { mutableStateOf<List<ArtistPopularTrack>>(emptyList()) }
    var ratedTracks by remember { mutableStateOf<List<RatingItem>>(emptyList()) }
    var totalTrackCount by remember { mutableIntStateOf(0) }
    var totalPlayCount by remember { mutableIntStateOf(0) }
    var similarArtists by remember { mutableStateOf<List<String>>(emptyList()) }
    var styles by remember { mutableStateOf<List<String>>(emptyList()) }
    var portraitArtPath by remember { mutableStateOf<String?>(null) }
    var resolvedArtist by remember(artistName) { mutableStateOf(artistName) }
    var followed by remember { mutableStateOf(false) }
    var albumSort by remember { mutableStateOf(ArtistAlbumSort.Newest) }
    var relatedVideos by remember { mutableStateOf<List<MusicVideoRelatedItem>>(emptyList()) }
    var albumCount by remember { mutableIntStateOf(0) }
    var aboutInfo by remember { mutableStateOf<com.bockmedia.console.data.api.dto.ArtistDetailAbout?>(null) }
    var appearsOnAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var allAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var selectedTab by remember { mutableStateOf("music") }
    var collapsedSections by remember { mutableStateOf(setOf<String>()) }
    var showShare by remember { mutableStateOf(false) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showMixMuse by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showPopularExpanded by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<ArtistPopularTrack?>(null) }
    var albumMenu by remember { mutableStateOf<AlbumItem?>(null) }
    var addToPlaylist by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addToRoom by remember { mutableStateOf<Triple<String, String, String?>?>(null) }

    val playTarget = remember(resolvedArtist) { PlayTarget.Artist(resolvedArtist) }
    val shuffleTarget = remember(resolvedArtist) {
        PlayTarget.Radio(resolvedArtist, PlayTarget.RadioSeedKind.Artist, resolvedArtist)
    }
    val discoverySeed = remember(resolvedArtist) {
        DiscoverySeed(
            kind = DiscoverySeedKind.album,
            title = resolvedArtist,
            album = null,
            artist = resolvedArtist,
        )
    }
    val heroArtUrl = rememberArtworkUrl(
        repository = repository,
        title = resolvedArtist,
        artPath = portraitArtPath,
        artistName = resolvedArtist,
        variantKey = "portrait-$resolvedArtist",
    )
    val heroAccent = rememberArtworkAccentColor(resolvedArtist, heroArtUrl)

    ImmersiveDarkStatusBar()

    val listState = rememberLazyListState()
    val showStickyHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 180)
        }
    }
    val sortedStudioAlbums = remember(studioAlbums, albumSort) {
        ArtistDetailRules.sortAlbums(studioAlbums, albumSort)
    }
    val sortedSingleAlbums = remember(singleAlbums, albumSort) {
        ArtistDetailRules.sortAlbums(singleAlbums, albumSort)
    }

    fun applyDetail(
        detail: com.bockmedia.console.data.api.dto.ArtistDetailResponse,
        portrait: String?,
        rated: List<RatingItem>,
    ) {
        resolvedArtist = detail.artist.takeIf { it.isNotBlank() } ?: artistName
        portraitArtPath = portrait
        followed = detail.followed
        ratedTracks = ArtistDetailRules.highlyRatedTracksForArtist(rated, resolvedArtist, artistName)
        val albums = detail.albums.map { it.toAlbumItem() }
        val (studio, singles) = ArtistDetailRules.splitAlbums(albums)
        studioAlbums = studio
        singleAlbums = singles
        totalTrackCount = detail.trackCount.takeIf { it > 0 } ?: albums.sumOf { it.tracks }
        totalPlayCount = detail.totalPlays
        albumCount = detail.albumCount.takeIf { it > 0 } ?: albums.size
        aboutInfo = detail.about
        appearsOnAlbums = detail.appearsOn.map { it.toAlbumItem() }
        allAlbums = albums
        popularTracks = detail.topTracks.mapIndexed { index, track ->
            track.toPopularTrack(index + 1)
        }
        similarArtists = detail.similarArtists
            .map { it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals(resolvedArtist, ignoreCase = true) }
            .distinct()
        styles = ArtistDetailRules.topGenresFromTracks(detail.topTracks.map { it.genre })
    }

    LaunchedEffect(artistName) {
        selectedTab = "music"
        showPopularExpanded = false
        resolvedArtist = artistName
        ArtistDetailSessionCache.getUiState(artistName)?.let { ui ->
            collapsedSections = ui.collapsedSections
        }
        var cachedDetail: com.bockmedia.console.data.api.dto.ArtistDetailResponse? = null
        ArtistDetailSessionCache.get(artistName)?.let { (cached, portrait) ->
            cachedDetail = cached
            applyDetail(cached, portrait, emptyList())
        }
        loading = cachedDetail == null
        runCatching {
            coroutineScope {
                val detailDeferred = async { repository.artistDetail(artistName) }
                val portraitDeferred = async { repository.artistPortraitPath(artistName) }
                val ratedDeferred = async { repository.ratedSongs() }
                val videosDeferred = async {
                    runCatching { repository.musicVideoRelated(artistName) }.getOrNull()?.items.orEmpty()
                }

                val detail = detailDeferred.await()
                val portrait = portraitDeferred.await()
                val rated = ratedDeferred.await()
                relatedVideos = videosDeferred.await()

                val hasContent = detail.albums.isNotEmpty() || detail.topTracks.isNotEmpty()
                if (hasContent || cachedDetail == null) {
                    applyDetail(detail, portrait, rated)
                    if (hasContent) {
                        ArtistDetailSessionCache.put(resolvedArtist, detail, portrait)
                    }
                } else {
                    portraitArtPath = portrait ?: portraitArtPath
                    ratedTracks = ArtistDetailRules.highlyRatedTracksForArtist(rated, resolvedArtist, artistName)
                    followed = detail.followed
                }

                if (studioAlbums.isEmpty() && popularTracks.isEmpty()) {
                    var resp = repository.albums(
                        page = 1,
                        search = "",
                        artist = resolvedArtist,
                        limit = 200,
                        sort = "year",
                    )
                    if (resp.items.isEmpty()) {
                        resp = repository.albums(page = 1, search = artistName, limit = 200, sort = "year")
                    }
                    val fallbackAlbums = resp.items.filter {
                        ArtistDetailRules.matchesArtist(it.artist ?: resolvedArtist, resolvedArtist)
                    }
                    if (fallbackAlbums.isNotEmpty()) {
                        val (fbStudio, fbSingles) = ArtistDetailRules.splitAlbums(fallbackAlbums)
                        studioAlbums = fbStudio
                        singleAlbums = fbSingles
                        totalTrackCount = fallbackAlbums.sumOf { it.tracks }
                        resolvedArtist = fallbackAlbums.firstNotNullOfOrNull { it.artist } ?: resolvedArtist
                    }
                }
            }
        }.onFailure { err ->
            scope.launch {
                snackbarHostState?.showSnackbar(err.message ?: "Could not load artist")
            }
            if (isServerConnectionError(err)) {
                runCatching { BockMediaApp.get(context).recoverFromConnectionFailure() }
            }
        }
        loading = false
    }

    LaunchedEffect(selectedTab) {
        runCatching { listState.scrollToItem(0) }
    }

    LaunchedEffect(listState, resolvedArtist, selectedTab, collapsedSections) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (index < listState.layoutInfo.totalItemsCount) {
                ArtistDetailSessionCache.saveUiState(
                    resolvedArtist,
                    ArtistDetailSessionCache.UiState(
                        scrollIndex = index,
                        scrollOffset = offset,
                        collapsedSections = collapsedSections,
                        selectedTab = selectedTab,
                    ),
                )
            }
        }
    }

    fun clampScrollAfterItemCountChange() {
        scope.launch {
            runCatching {
                val maxIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                if (listState.firstVisibleItemIndex > maxIndex) {
                    listState.scrollToItem(maxIndex)
                }
            }
        }
    }

    fun launchLocalPlaylist(tracks: List<PlaylistTrack>, title: String, shuffle: Boolean) {
        scope.launch {
            if (tracks.isEmpty()) {
                snackbarHostState?.showSnackbar("No playable tracks")
                return@launch
            }
            runCatching {
                repository.playDiscoveryTracksLocally(
                    context = context,
                    tracks = tracks,
                    title = title,
                    shuffle = shuffle,
                    activeTarget = playTarget,
                )
            }.onFailure {
                snackbarHostState?.showSnackbar(it.message ?: "Playback failed")
                return@launch
            }
            val err = LocalPlaybackController.state.value.error
            if (err != null) {
                snackbarHostState?.showSnackbar(err)
            } else {
                onLocalPlayStarted()
            }
        }
    }

    fun popularTracksAsPlaylist(): List<PlaylistTrack> =
        popularTracks.mapNotNull { track ->
            track.path?.takeIf { it.isNotBlank() }?.let { path ->
                PlaylistTrack(
                    path = path,
                    title = track.title,
                    artist = track.artist ?: resolvedArtist,
                    album = track.album,
                )
            }
        }

    val latestAlbum = remember(allAlbums) { ArtistDetailRules.latestAlbum(allAlbums) }
    val appearsOnNames = remember(appearsOnAlbums) { appearsOnAlbums.map { it.name }.toSet() }

    if (showShare) {
        DetailShareSheet(
            title = resolvedArtist,
            deepLink = "bockmedia://artist/${java.net.URLEncoder.encode(resolvedArtist, "UTF-8")}",
            repository = repository,
            remoteOk = remoteOk,
            onAddToPlaylist = { showShare = false },
            onAddToRoom = { showShare = false },
            onDismiss = { showShare = false },
            onCopied = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
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

    Box(
        Modifier
            .fillMaxSize()
            .testTag(BockTestTags.ARTIST_DETAIL_BODY)
            .background(Color.Black),
    ) {
        if (loading && studioAlbums.isEmpty() && singleAlbums.isEmpty()) {
            LoadingBox(Modifier.fillMaxSize())
        } else {
            BockLazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "hero") {
                    SpotifyArtistHero(
                        artistName = resolvedArtist,
                        portraitUrl = heroArtUrl,
                        accentColor = heroAccent,
                        onBack = onBack,
                        onMore = { showMore = true },
                        onListenAgent = { onOpenListenAgent("play top songs from $resolvedArtist") },
                    )
                }
                item(key = "actions") {
                    Column {
                        ArtistLibraryStatsStrip(
                            trackCount = totalTrackCount,
                            albumCount = albumCount,
                            totalPlays = totalPlayCount,
                        )
                        SpotifyArtistActions(
                            totalTrackCount = totalTrackCount,
                            totalPlayCount = totalPlayCount,
                            albumCount = albumCount,
                            remoteOk = remoteOk,
                            playTarget = playTarget,
                            followed = followed,
                            onFollowToggle = {
                                scope.launch {
                                    val nextStars = if (followed) 0 else 3
                                    runCatching {
                                        repository.setRating(
                                            kind = RatingKind.Artist,
                                            id = resolvedArtist,
                                            stars = nextStars,
                                            title = resolvedArtist,
                                        )
                                    }
                                    followed = nextStars >= 3
                                }
                            },
                            onPlay = { onPlay(playTarget) },
                            onShuffle = { onPlay(shuffleTarget) },
                            onRadio = { showDiscovery = true },
                        )
                    }
                }
                item(key = "section-tabs") {
                    ArtistSectionTabs(selected = selectedTab, onSelect = {
                        selectedTab = it
                        clampScrollAfterItemCountChange()
                    })
                }
                if (selectedTab == "about") {
                    item(key = "about") {
                        ArtistAboutSection(about = aboutInfo, artistName = resolvedArtist, trackCount = totalTrackCount)
                    }
                }
                if (selectedTab == "music") {
                if (latestAlbum != null) {
                    item(key = "latest-album") {
                        ArtistLatestAlbumCard(
                            album = latestAlbum!!,
                            artistName = resolvedArtist,
                            repository = repository,
                            onClick = { onOpenAlbum(latestAlbum!!.name, latestAlbum!!.artist ?: resolvedArtist) },
                        )
                    }
                }
                if (ratedTracks.isNotEmpty()) {
                    item(key = "rated-row") {
                        SpotifyRatedRow(
                            repository = repository,
                            artistName = resolvedArtist,
                            artUrl = heroArtUrl,
                            ratedCount = ratedTracks.size,
                            onClick = {
                                val playable = ratedTracks.filter { it.id.isNotBlank() }
                                launchLocalPlaylist(
                                    tracks = playable.map { row ->
                                        PlaylistTrack(
                                            path = row.id,
                                            title = row.title,
                                            artist = row.artist,
                                            album = row.album,
                                        )
                                    },
                                    title = "Highly rated · $resolvedArtist",
                                    shuffle = true,
                                )
                            },
                        )
                    }
                }
                if (popularTracks.isNotEmpty()) {
                    item(key = "popular-header") {
                        CollapsibleSectionHeader(
                            title = "Popular",
                            expanded = "popular" !in collapsedSections,
                            onToggle = {
                                collapsedSections = if ("popular" in collapsedSections) {
                                    collapsedSections - "popular"
                                } else {
                                    collapsedSections + "popular"
                                }
                                clampScrollAfterItemCountChange()
                            },
                            onPlay = {
                                launchLocalPlaylist(
                                    tracks = popularTracksAsPlaylist(),
                                    title = "Popular · $resolvedArtist",
                                    shuffle = false,
                                )
                            },
                            playContentDescription = "Play all popular tracks",
                        )
                    }
                    if ("popular" !in collapsedSections) {
                    val visiblePopular = if (showPopularExpanded) {
                        popularTracks
                    } else {
                        popularTracks.take(POPULAR_PREVIEW_COUNT)
                    }
                    items(
                        visiblePopular.size,
                        key = { idx -> "pop-$idx-${visiblePopular[idx].path ?: visiblePopular[idx].title}" },
                    ) { index ->
                        val track = visiblePopular[index]
                        SpotifyPopularTrackRow(
                            repository = repository,
                            track = track,
                            onClick = {
                                track.path?.let { path ->
                                    onPlay(PlayTarget.Song(path, track.title))
                                }
                            },
                            onMenu = { trackMenu = track },
                            onLikeToggle = {
                                scope.launch {
                                    val path = track.path ?: return@launch
                                    val next = if (track.liked) 0 else 5
                                    runCatching {
                                        repository.setRating(
                                            kind = RatingKind.Song,
                                            id = path,
                                            stars = next,
                                            title = track.title,
                                            artist = track.artist,
                                            album = track.album,
                                        )
                                    }
                                    popularTracks = popularTracks.map {
                                        if (it.path == path) it.copy(liked = next >= 5, rating = next) else it
                                    }
                                }
                            },
                        )
                    }
                    if (!showPopularExpanded && popularTracks.size > POPULAR_PREVIEW_COUNT) {
                        item(key = "popular-see-all") {
                            TextButton(
                                onClick = { showPopularExpanded = true },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text("See all", color = BockGreen)
                            }
                        }
                    }
                    }
                }
                if (relatedVideos.isNotEmpty()) {
                    item(key = "videos-header") {
                        SpotifySectionTitle("Videos")
                    }
                    item(key = "videos-row") {
                        ArtistVideosRow(
                            videos = relatedVideos,
                            onVideoClick = { video ->
                                val match = popularTracks.firstOrNull { track ->
                                    video.title.contains(track.title, ignoreCase = true)
                                }
                                match?.path?.let { path ->
                                    onPlay(PlayTarget.Song(path, match.title))
                                } ?: scope.launch {
                                    runCatching {
                                        repository.musicVideo(
                                            video.title.substringAfter("-").trim().ifBlank { video.title },
                                            video.artist.ifBlank { resolvedArtist },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (sortedStudioAlbums.isNotEmpty()) {
                    item(key = "albums-header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Albums",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                ArtistDetailRules.yearRange(sortedStudioAlbums)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.55f),
                                    )
                                }
                            }
                            TextButton(onClick = {
                                ArtistDiscographyNavCache.albums = allAlbums.ifEmpty { sortedStudioAlbums + sortedSingleAlbums }
                                ArtistDiscographyNavCache.appearsOnNames = appearsOnNames
                                onOpenDiscography()
                            }) {
                                Text("See all", color = BockGreen)
                            }
                        }
                    }
                    item(key = "album-sort") {
                        ArtistAlbumSortRow(sort = albumSort, onSort = { albumSort = it })
                    }
                    item(key = "albums-row") {
                        ArtistAlbumGridRow(
                            albums = sortedStudioAlbums,
                            artistName = resolvedArtist,
                            repository = repository,
                            remoteOk = remoteOk,
                            onOpenAlbum = onOpenAlbum,
                            onPlay = onPlay,
                            onAlbumMenu = { albumMenu = it },
                        )
                    }
                }
                if (sortedSingleAlbums.isNotEmpty()) {
                    item(key = "singles-header") {
                        SpotifySectionTitle(
                            title = "Singles & EPs",
                            subtitle = ArtistDetailRules.yearRange(sortedSingleAlbums),
                        )
                    }
                    item(key = "singles-row") {
                        ArtistAlbumGridRow(
                            albums = sortedSingleAlbums,
                            artistName = resolvedArtist,
                            repository = repository,
                            remoteOk = remoteOk,
                            onOpenAlbum = onOpenAlbum,
                            onPlay = onPlay,
                            onAlbumMenu = { albumMenu = it },
                        )
                    }
                }
                if (appearsOnAlbums.isNotEmpty()) {
                    item(key = "appears-header") {
                        SpotifySectionTitle("Appears on")
                    }
                    item(key = "appears-row") {
                        ArtistAlbumGridRow(
                            albums = appearsOnAlbums,
                            artistName = resolvedArtist,
                            repository = repository,
                            remoteOk = remoteOk,
                            onOpenAlbum = onOpenAlbum,
                            onPlay = onPlay,
                            onAlbumMenu = { albumMenu = it },
                        )
                    }
                }
                if (similarArtists.isNotEmpty()) {
                    item(key = "similar-header") {
                        SpotifySectionTitle("Fans also like")
                    }
                    items(similarArtists.size, key = { "similar-$it" }) { index ->
                        ArtistNameRow(
                            repository = repository,
                            name = similarArtists[index],
                            onClick = { onOpenArtist(similarArtists[index]) },
                        )
                    }
                }
                if (styles.isNotEmpty()) {
                    item(key = "styles-header") {
                        SpotifySectionTitle("Genres")
                    }
                    item(key = "styles-chips") {
                        ArtistStylesRow(styles = styles, onGenreClick = onOpenGenre)
                    }
                }
                }
                item(key = "bottom-spacer") {
                    Spacer(Modifier.height(24.dp))
                }
            }
            AnimatedVisibility(
                visible = showStickyHeader,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ArtistStickyMiniHeader(
                    artistName = resolvedArtist,
                    onBack = onBack,
                    onPlay = { onPlay(playTarget) },
                )
            }
        }
    }

    trackMenu?.let { track ->
        val path = track.path
        if (path != null) {
            PlexampEntitySheet(
                title = track.title,
                rating = RatingTarget(
                    kind = RatingKind.Song,
                    id = path,
                    title = track.title,
                    artist = track.artist ?: artistName,
                    album = track.album,
                ),
                repository = repository,
                actions = buildList {
                    add(PlexampSheetAction("Play", Icons.Default.PlayArrow, onClick = {
                        onPlay(PlayTarget.Song(path, track.title))
                        trackMenu = null
                    }))
                    add(PlexampSheetAction("Add to playlist", Icons.Default.PlaylistAdd, onClick = {
                        addToPlaylist = path to track.title
                        trackMenu = null
                    }))
                    if (remoteOk) {
                        add(PlexampSheetAction("Add to room", Icons.Default.Add, onClick = {
                            addToRoom = Triple(path, track.title, track.artist ?: artistName)
                            trackMenu = null
                        }))
                    }
                },
                onDismiss = { trackMenu = null },
            )
        }
    }

    albumMenu?.let { album ->
        val albumArtist = album.artist ?: artistName
        PlexampEntitySheet(
            title = album.name,
            rating = RatingTarget(
                kind = RatingKind.Album,
                id = albumRatingId(album.name, albumArtist),
                title = album.name,
                artist = albumArtist,
                album = album.name,
            ),
            repository = repository,
            actions = listOf(
                PlexampSheetAction("Play album", Icons.Default.PlayArrow, onClick = {
                    onPlay(PlayTarget.Album(album.name, albumArtist))
                    albumMenu = null
                }),
                PlexampSheetAction("Download album", Icons.Default.Download, onClick = {
                    scope.launch {
                        com.bockmedia.console.local.OfflineDownloadManager.download(
                            context,
                            PlayTarget.Album(album.name, albumArtist),
                        )
                    }
                    albumMenu = null
                }),
                PlexampSheetAction("Open album", Icons.AutoMirrored.Filled.KeyboardArrowRight, onClick = {
                    onOpenAlbum(album.name, albumArtist)
                    albumMenu = null
                }),
            ),
            onDismiss = { albumMenu = null },
        )
    }

    if (showMore) {
        PlexampEntitySheet(
            title = resolvedArtist,
            rating = RatingTarget(
                kind = RatingKind.Artist,
                id = resolvedArtist,
                title = resolvedArtist,
            ),
            repository = repository,
            actions = listOf(
                PlexampSheetAction("Share…", Icons.Default.Link, onClick = {
                    showMore = false
                    showShare = true
                }),
                PlexampSheetAction("Resonance radio", Icons.Outlined.GraphicEq, onClick = {
                    showMore = false
                    showDiscovery = true
                }),
                PlexampSheetAction("Mix Muse playlist…", Icons.Default.AutoAwesome, onClick = {
                    showMore = false
                    showMixMuse = true
                }),
                PlexampSheetAction("Download artist", Icons.Default.Download, onClick = {
                    scope.launch {
                        com.bockmedia.console.local.OfflineDownloadManager.download(context, playTarget)
                    }
                    showMore = false
                }),
            ),
            onDismiss = { showMore = false },
        )
    }

    addToPlaylist?.let { (path, title) ->
        AddToPlaylistSheet(
            repository = repository,
            trackPath = path,
            trackTitle = title,
            onDismiss = { addToPlaylist = null },
            onAdded = { msg ->
                addToPlaylist = null
                scope.launch { snackbarHostState?.showSnackbar(msg) }
            },
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
            onSuccess = { msg ->
                scope.launch { snackbarHostState?.showSnackbar(msg) }
                addToRoom = null
            },
            onError = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
    }
}

@Composable
private fun ArtistAlbumGridRow(
    albums: List<AlbumItem>,
    artistName: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onOpenAlbum: (String, String?) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onAlbumMenu: (AlbumItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        albums.forEach { album ->
            val albumArtist = album.artist ?: artistName
            ArtistDiscographyCard(
                album = album,
                repository = repository,
                remoteOk = remoteOk,
                onClick = { onOpenAlbum(album.name, albumArtist) },
                onPlay = { onPlay(PlayTarget.Album(album.name, albumArtist)) },
                onMenu = { onAlbumMenu(album) },
            )
        }
    }
}

@Composable
private fun ArtistDiscographyCard(
    album: AlbumItem,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = album.name,
        artPath = album.artPath,
        albumName = album.name,
        albumArtist = album.artist,
        variantKey = "${album.name}|${album.artist}",
    )
    Column(
        Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            BockArtwork(
                model = artUrl,
                title = album.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp)),
                shape = RoundedCornerShape(6.dp),
                contentScale = ContentScale.Crop,
                fallbackFontSize = 22.sp,
            )
            PlayDownloadActions(
                playTarget = PlayTarget.Album(album.name, album.artist),
                remoteOk = remoteOk,
                onPlay = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                showDownload = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = onMenu,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Album options",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SpotifyArtistHero(
    artistName: String,
    portraitUrl: String?,
    accentColor: Color = Color(0xFF1A1A2E),
    onBack: () -> Unit,
    onMore: () -> Unit,
    onListenAgent: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(heroGradientBrush(accentColor)),
        )
        BockArtwork(
            model = portraitUrl,
            title = artistName,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-16).dp)
                .size(168.dp)
                .clip(CircleShape),
            shape = CircleShape,
            contentScale = ContentScale.Crop,
            fallbackFontSize = 48.sp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.95f),
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            ListenAgentMicButton(onClick = onListenAgent)
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
            }
        }
        Text(
            artistName,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpotifyArtistActions(
    totalTrackCount: Int,
    totalPlayCount: Int,
    albumCount: Int = 0,
    remoteOk: Boolean,
    playTarget: PlayTarget.Artist,
    followed: Boolean,
    onFollowToggle: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = Color.White.copy(alpha = 0.85f))
            }
            IconButton(onClick = onRadio) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "Radio", tint = Color.White.copy(alpha = 0.85f))
            }
            FilterChip(
                selected = followed,
                onClick = onFollowToggle,
                label = { Text(if (followed) "Following" else "Follow") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BockGreen.copy(alpha = 0.25f),
                    selectedLabelColor = BockGreen,
                    labelColor = Color.White.copy(alpha = 0.85f),
                ),
            )
            Spacer(Modifier.weight(1f))
            PlayDownloadActions(
                playTarget = playTarget,
                remoteOk = remoteOk,
                onPlay = onPlay,
                showDownload = true,
            )
        }
    }
}

@Composable
private fun SpotifySectionTab(label: String, selected: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (selected) {
            Box(
                Modifier
                    .width(48.dp)
                    .height(2.dp)
                    .background(BockGreen),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SpotifySectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
}

@Composable
private fun SpotifyRatedRow(
    repository: BockMediaRepository,
    artistName: String,
    artUrl: String?,
    ratedCount: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BockArtwork(
                model = artUrl,
                title = artistName,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                shape = CircleShape,
                fallbackFontSize = 16.sp,
            )
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = BockGreen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .background(Color.Black, CircleShape)
                    .padding(2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Highly rated", fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "$ratedCount tracks · $artistName",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun SpotifyPopularTrackRow(
    repository: BockMediaRepository,
    track: ArtistPopularTrack,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onLikeToggle: () -> Unit = {},
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = track.title,
        artPath = track.artPath,
        albumName = track.album,
        variantKey = "${track.title}|${track.album}",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${track.rank}",
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Spacer(Modifier.width(12.dp))
        BockArtwork(
            model = artUrl,
            title = track.title,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
            shape = RoundedCornerShape(4.dp),
            fallbackFontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = track.playCount?.let { "${formatPlayCount(it)} plays" }
                ?: track.album
                ?: track.durationSeconds?.let { formatTrackDuration(it) }
                ?: ""
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TrackLikeButton(liked = track.liked, onToggle = onLikeToggle)
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.MoreVert, contentDescription = "Track options", tint = Color.White.copy(alpha = 0.55f))
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
private fun ArtistStylesRow(
    styles: List<String>,
    onGenreClick: (String) -> Unit = {},
) {
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
                modifier = Modifier.clickable { onGenreClick(style) },
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

private fun ArtistDetailTrack.toPopularTrack(rank: Int) = ArtistPopularTrack(
    rank = rank,
    title = title?.trim().orEmpty().ifBlank { "—" },
    path = path,
    artPath = path,
    album = album,
    artist = artist,
    playCount = playCount.takeIf { it > 0 },
    durationSeconds = durationSeconds?.toInt(),
    liked = liked,
    rating = rating,
)

private fun formatPlayCount(count: Int): String = when {
    count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 10_000 -> String.format("%,d", count)
    else -> count.toString()
}

@Composable
private fun ArtistStickyMiniHeader(
    artistName: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.92f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                artistName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = BockGreen)
            }
        }
    }
}

@Composable
private fun ArtistAlbumSortRow(sort: ArtistAlbumSort, onSort: (ArtistAlbumSort) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtistAlbumSort.entries.forEach { option ->
            FilterChip(
                selected = sort == option,
                onClick = { onSort(option) },
                label = {
                    Text(
                        when (option) {
                            ArtistAlbumSort.Newest -> "Newest"
                            ArtistAlbumSort.Oldest -> "Oldest"
                            ArtistAlbumSort.NameAz -> "A–Z"
                        },
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BockGreen.copy(alpha = 0.25f),
                    selectedLabelColor = BockGreen,
                    labelColor = Color.White.copy(alpha = 0.75f),
                ),
            )
        }
    }
}

@Composable
private fun ArtistVideosRow(
    videos: List<MusicVideoRelatedItem>,
    onVideoClick: (MusicVideoRelatedItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        videos.forEach { video ->
            Column(
                Modifier
                    .width(160.dp)
                    .clickable { onVideoClick(video) },
            ) {
                BockArtwork(
                    model = video.thumbnail,
                    title = video.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp)),
                    shape = RoundedCornerShape(6.dp),
                    contentScale = ContentScale.Crop,
                    fallbackFontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
