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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import com.bockmedia.console.local.OfflineDownloadManager
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.List) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recents) }
    var search by remember { mutableStateOf("") }

    suspend fun load() {
        LibraryCache.getIfFresh(filter, search)?.let { fresh ->
            items = fresh
            loading = false
            refreshing = false
            return
        }
        if (items.isEmpty()) loading = true
        runCatching {
            val fresh = LibraryLoader.load(repository, context, filter, search)
            items = fresh
            LibraryCache.put(filter, search, fresh)
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(filter, search) {
        if (search.isNotBlank()) delay(300)
        // Paint cached items for this filter/search instantly; otherwise show a spinner
        // (never leave the previous filter's items on screen while the new set loads).
        val seeded = LibraryCache.peek(filter, search)
        if (seeded != null) {
            items = seeded
            loading = false
        } else {
            items = emptyList()
            loading = true
        }
        load()
    }

    val sorted = remember(items, sort) {
        when (sort) {
            LibrarySort.Name -> items.sortedBy { it.title.lowercase() }
            LibrarySort.Recents -> items.sortedWith(
                compareByDescending<LibraryItem> { it.sortDate }.thenBy { it.title.lowercase() },
            )
        }
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; LibraryCache.invalidate(); scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                viewMode = viewMode,
                onToggleView = {
                    viewMode = if (viewMode == LibraryViewMode.List) LibraryViewMode.Grid else LibraryViewMode.List
                },
                sort = sort,
                onSortChange = { sort = it },
                onOpenFavorites = onOpenFavorites,
            )
            SearchField(search, { search = it }, "Search in Your Library", modifier = Modifier.padding(horizontal = 16.dp))
            LibraryFilterRow(
                selected = filter,
                onSelect = { filter = it },
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (filter == LibraryFilter.All || filter == LibraryFilter.Playlists) {
                TextButton(
                    onClick = onOpenPlaylists,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text("Manage playlists")
                }
            }
            when {
                loading && items.isEmpty() -> LoadingBox(Modifier.weight(1f))
                sorted.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
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
                    modifier = Modifier.weight(1f),
                    onClick = { item -> handleLibraryClick(item, onPlay, onOpenPlaylist, onOpenArtist, onOpenAlbum) },
                    onPlay = onPlay,
                )
                else -> LibraryList(
                    items = sorted,
                    repository = repository,
                    remoteOk = remoteOk,
                    modifier = Modifier.weight(1f),
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Your Library",
            style = MaterialTheme.typography.headlineSmall,
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
    BockLazyColumn(modifier.padding(horizontal = 8.dp)) {
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
        modifier = modifier.padding(horizontal = 16.dp),
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
    val artUrl by produceState<String?>(initialValue = null, item.id) {
        value = when (item.kind) {
            LibraryItemKind.Playlist -> item.playlistId?.let { repository.artworkUrlForPlaylist(it, item.id) }
            LibraryItemKind.Artist -> item.artistName?.let { repository.resolveArtistArtUrl(it) }
            LibraryItemKind.Album -> repository.resolveAlbumArtUrl(item.albumName ?: item.title, item.artistName)
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
    )
}
