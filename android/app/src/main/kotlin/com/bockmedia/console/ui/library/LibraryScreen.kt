package com.bockmedia.console.ui.library

import androidx.compose.foundation.horizontalScroll
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.toPlayTarget
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.theme.HomePillActive
import com.bockmedia.console.ui.theme.HomePillInactive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PillShape = RoundedCornerShape(50)

@Composable
fun LibraryScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit = {},
    onAccountNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var libraryData by remember { mutableStateOf(LibrarySessionCache.peek()) }
    var searchItems by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var loading by remember { mutableStateOf(LibrarySessionCache.peek() == null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.List) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recents) }
    var search by remember { mutableStateOf("") }

    // Downloads are read live from the offline store, not the cached LibraryData
    // bucket — otherwise a freshly downloaded playlist won't appear here until the
    // whole library cache is rebuilt (so it diverged from Home > Downloads).
    val downloadStatuses by OfflineDownloadManager.statuses.collectAsState()
    val liveDownloads = remember(downloadStatuses) {
        downloadStatuses.values
            .filter { it.state == DownloadState.Complete }
            .map { it.manifest }
            .map { manifest ->
                LibraryItem(
                    id = "dl-${manifest.id}",
                    title = manifest.title,
                    subtitle = "${manifest.tracks.size} tracks · Offline",
                    kind = LibraryItemKind.Downloaded,
                    playTarget = manifest.toPlayTarget(),
                    artPath = manifest.coverArtPath,
                    playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId,
                    sortDate = manifest.lastSyncedAtMs.takeIf { it > 0 } ?: manifest.downloadedAtMs,
                )
            }
    }

    val displayItems = remember(libraryData, searchItems, filter, search, liveDownloads) {
        when {
            filter == LibraryFilter.Downloaded ->
                if (search.isBlank()) liveDownloads
                else liveDownloads.filter { it.title.contains(search, ignoreCase = true) }
            search.isNotBlank() -> searchItems.orEmpty()
            else -> libraryData?.forFilter(filter).orEmpty()
        }
    }

    suspend fun prefetchArt(items: List<LibraryItem>) {
        LibraryArtPrefetch.warm(context, repository, items)
    }

    fun libraryNeedsNetworkRefresh(data: LibraryData?): Boolean {
        if (data == null) return true
        if (data.artists.isEmpty() && data.albums.isEmpty() && data.playlists.isNotEmpty()) return true
        return LibrarySessionCache.getIfFresh() == null
    }

    suspend fun refreshFromNetwork() {
        runCatching {
            val fresh = LibraryLoader.loadBuckets(repository, context)
            libraryData = fresh
            LibrarySessionCache.put(fresh)
            LibraryCachePersistence.save(context, fresh)
        }
        loading = false
        refreshing = false
        libraryData?.let { prefetchArt(it.forFilter(filter)) }
    }

    suspend fun bootstrapLibrary() {
        LibrarySessionCache.peek()?.let { cached ->
            libraryData = cached
            loading = false
        } ?: LibraryCachePersistence.load(context)?.let { disk ->
            libraryData = disk
            LibrarySessionCache.put(disk)
            loading = false
        }
        if (libraryNeedsNetworkRefresh(libraryData)) {
            if (libraryData == null) loading = true
            refreshFromNetwork()
        } else {
            libraryData?.let { prefetchArt(it.forFilter(filter)) }
            if (LibrarySessionCache.getIfFresh() == null && libraryData != null) {
                scope.launch { refreshFromNetwork() }
            }
        }
    }

    LaunchedEffect(Unit) {
        OfflineDownloadManager.refresh(context)
        bootstrapLibrary()
    }

    LaunchedEffect(filter) {
        libraryData?.let { data ->
            scope.launch { prefetchArt(data.forFilter(filter)) }
        }
    }

    LaunchedEffect(filter, search) {
        if (search.isBlank()) {
            searchItems = null
            return@LaunchedEffect
        }
        delay(300)
        searchItems = null
        runCatching {
            searchItems = LibraryLoader.search(repository, context, filter, search)
            prefetchArt(searchItems.orEmpty())
        }
    }

    val sorted = remember(displayItems, sort) {
        when (sort) {
            LibrarySort.Name -> displayItems.sortedBy { it.title.lowercase() }
            LibrarySort.Recents -> displayItems.sortedWith(
                compareByDescending<LibraryItem> { it.sortDate }.thenBy { it.title.lowercase() },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                viewMode = viewMode,
                onToggleView = {
                    viewMode = if (viewMode == LibraryViewMode.List) LibraryViewMode.Grid else LibraryViewMode.List
                },
                sort = sort,
                onSortChange = { sort = it },
                onOpenFavorites = onOpenFavorites,
                onAccountNavigate = onAccountNavigate,
            )
        SearchField(search, { search = it }, "Search in Your Library", modifier = Modifier.padding(horizontal = 16.dp))
        LibraryFilterRow(
            selected = filter,
            onSelect = { filter = it },
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        if (filter == LibraryFilter.All || filter == LibraryFilter.Playlists) {
            TextButton(
                onClick = onOpenPlaylists,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("Manage playlists")
            }
        }
        BockPullRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                LibrarySessionCache.invalidate()
                scope.launch { refreshFromNetwork() }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                loading && displayItems.isEmpty() -> LoadingBox(Modifier.fillMaxSize())
                sorted.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            filter == LibraryFilter.Downloaded && search.isBlank() ->
                                "Nothing downloaded yet — tap download on Home tiles or long-press for more."
                            search.isNotBlank() ->
                                "No results for \"$search\"."
                            else ->
                                "No items found in your library."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                viewMode == LibraryViewMode.Grid -> LibraryGrid(
                    items = sorted,
                    repository = repository,
                    remoteOk = remoteOk,
                    modifier = Modifier.fillMaxSize().testTag(BockTestTags.LIBRARY_LIST),
                    onClick = { item -> handleLibraryClick(item, onPlay, onOpenPlaylist, onOpenArtist, onOpenAlbum) },
                    onPlay = onPlay,
                )
                else -> LibraryList(
                    items = sorted,
                    repository = repository,
                    remoteOk = remoteOk,
                    modifier = Modifier.fillMaxSize().testTag(BockTestTags.LIBRARY_LIST),
                    onClick = { item -> handleLibraryClick(item, onPlay, onOpenPlaylist, onOpenArtist, onOpenAlbum) },
                    onPlay = onPlay,
                )
            }
        }
    }
}

private fun handleLibraryClick(
    item: LibraryItem,
    onPlay: (PlayTarget) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    when (item.kind) {
        LibraryItemKind.Playlist -> item.playlistId?.let(onOpenPlaylist)
        LibraryItemKind.Artist -> item.artistName?.let(onOpenArtist)
        LibraryItemKind.Album -> item.albumName?.let(onOpenAlbum)
        LibraryItemKind.Downloaded -> onPlay(item.playTarget)
    }
}

@Composable
private fun LibraryHeader(
    viewMode: LibraryViewMode,
    onToggleView: () -> Unit,
    sort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    onOpenFavorites: () -> Unit,
    onAccountNavigate: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Your Library",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenFavorites) {
            Icon(Icons.Default.Star, contentDescription = "Favorites", tint = BockGreen)
        }
        var sortMenu by remember { mutableStateOf(false) }
        TextButton(onClick = { sortMenu = true }) {
            Text(if (sort == LibrarySort.Recents) "Recents" else "A–Z", fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
            DropdownMenuItem(
                text = { Text("Recents") },
                onClick = { onSortChange(LibrarySort.Recents); sortMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Alphabetical") },
                onClick = { onSortChange(LibrarySort.Name); sortMenu = false },
            )
        }
        IconButton(onClick = onToggleView) {
            Icon(
                if (viewMode == LibraryViewMode.List) Icons.Default.GridView else Icons.Default.List,
                contentDescription = "Toggle view",
            )
        }
        AccountMenuButton(onAccountNavigate)
    }
}

@Composable
private fun LibraryFilterRow(
    selected: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inactive = HomePillInactive
    val active = HomePillActive
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { f ->
            val isActive = f == selected
            Surface(
                onClick = { onSelect(f) },
                shape = PillShape,
                color = if (isActive) active else inactive,
            ) {
                Text(
                    f.label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) Color(0xFF0F1419) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LibraryList(
    items: List<LibraryItem>,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    modifier: Modifier = Modifier,
    onClick: (LibraryItem) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    BockLazyColumn(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(items, key = { it.id }) { item ->
            LibraryItemRow(
                item = item,
                repository = repository,
                onClick = { onClick(item) },
                trailing = {
                    PlayDownloadActions(
                        playTarget = item.playTarget,
                        remoteOk = remoteOk || item.kind == LibraryItemKind.Downloaded,
                        onPlay = { onPlay(item.playTarget) },
                        showDownload = item.kind != LibraryItemKind.Downloaded,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    items: List<LibraryItem>,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    modifier: Modifier = Modifier,
    onClick: (LibraryItem) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(items, key = { it.id }) { item ->
            LibraryGridTile(
                item = item,
                repository = repository,
                remoteOk = remoteOk || item.kind == LibraryItemKind.Downloaded,
                onClick = { onClick(item) },
                onPlay = { onPlay(item.playTarget) },
            )
        }
    }
}

@Composable
private fun LibraryItemRow(
    item: LibraryItem,
    repository: BockMediaRepository,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            LibraryItemArt(
                item = item,
                repository = repository,
                modifier = Modifier.size(56.dp),
                shape = if (item.kind == LibraryItemKind.Artist) CircleShape else RoundedCornerShape(6.dp),
            )
        },
        headlineContent = {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = trailing,
    )
}

@Composable
private fun LibraryGridTile(
    item: LibraryItem,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Column {
        Box(Modifier.clickable(onClick = onClick)) {
            LibraryItemArt(
                item = item,
                repository = repository,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = if (item.kind == LibraryItemKind.Artist) CircleShape else RoundedCornerShape(8.dp),
                fallbackFontSize = 28.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlayDownloadActions(
            playTarget = item.playTarget,
            remoteOk = remoteOk,
            onPlay = onPlay,
            showDownload = item.kind != LibraryItemKind.Downloaded,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }
}

@Composable
private fun LibraryItemArt(
    item: LibraryItem,
    repository: BockMediaRepository,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp),
    fallbackFontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
) {
    val baseUrl = remember(repository) { repository.peekBaseUrl() }
    var artUrl by remember(item.id, baseUrl, item.artPath, item.playlistId) {
        mutableStateOf(peekLibraryArtUrl(baseUrl, item))
    }
    LaunchedEffect(item.id, baseUrl, item.artPath, item.playlistId) {
        artUrl = peekLibraryArtUrl(baseUrl, item)
            ?: when (item.kind) {
                LibraryItemKind.Playlist -> item.playlistId?.let {
                    repository.artworkUrlForPlaylist(it, item.id)
                }
                LibraryItemKind.Artist -> item.artistName?.let { repository.resolveArtistArtUrl(it) }
                LibraryItemKind.Album -> repository.resolveAlbumArtUrl(
                    item.albumName ?: item.title,
                    item.artistName,
                )
                LibraryItemKind.Downloaded -> repository.resolveOfflineManifestArtUrl(
                    com.bockmedia.console.local.OfflineCollectionManifest(
                        id = item.id.removePrefix("dl-"),
                        title = item.title,
                        coverArtPath = item.artPath,
                        sourcePlaylistId = item.playlistId,
                    ),
                )
            } ?: item.artPath?.let { repository.artworkUrl(it) }
    }
    BockArtwork(
        model = artUrl,
        title = item.title,
        modifier = modifier,
        shape = shape,
        fallbackFontSize = fallbackFontSize,
        crossfadeMs = 0,
    )
}
