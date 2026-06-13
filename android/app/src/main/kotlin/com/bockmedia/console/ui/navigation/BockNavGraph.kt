package com.bockmedia.console.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.AlexaAuthMonitor
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.analytics.AnalyticsScreen
import com.bockmedia.console.ui.automation.AutomationScreen
import com.bockmedia.console.ui.components.PlayTargetLauncher
import com.bockmedia.console.ui.dashboard.DashboardScreen
import com.bockmedia.console.ui.devices.DevicesScreen
import com.bockmedia.console.ui.library.AlbumsScreen
import com.bockmedia.console.ui.library.ArtistsScreen
import com.bockmedia.console.ui.library.SongsScreen
import com.bockmedia.console.ui.nowplaying.NowPlayingScreen
import com.bockmedia.console.ui.playlists.PlaylistDetailScreen
import com.bockmedia.console.ui.playlists.PlaylistsScreen
import com.bockmedia.console.ui.rooms.RoomsScreen
import com.bockmedia.console.ui.routines.RoutinesScreen
import com.bockmedia.console.ui.search.SearchScreen
import com.bockmedia.console.ui.settings.SettingsScreen
import com.bockmedia.console.ui.watchfolders.WatchFoldersScreen
import kotlinx.coroutines.delay

/** Nested screens pushed on top of a drawer tab (no NavHost — crash-safe on device). */
private sealed class OverlayScreen {
    data class PlaylistDetail(val id: String) : OverlayScreen()
    data class AlbumsArtist(val artist: String) : OverlayScreen()
    data class SongsArtist(val artist: String) : OverlayScreen()
    data class SongsAlbum(val album: String, val artist: String?) : OverlayScreen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockApp(repository: BockMediaRepository, onChangeServer: () -> Unit, deepLinkRoute: String? = null) {
    var topRoute by rememberSaveable { mutableStateOf(BockRoute.Dashboard.route) }
    var overlay by remember { mutableStateOf<OverlayScreen?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var playTarget by remember { mutableStateOf<PlayTarget?>(null) }
    var remoteOk by remember { mutableStateOf(false) }
    var extrasReady by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        delay(400)
        runCatching { remoteOk = alexaControlsAvailable(repository.alexaRemoteStatus()) }
        extrasReady = true
    }

    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let { route ->
            val dest = route.removePrefix("#").trim()
            if (dest.isNotBlank()) topRoute = dest.substringBefore("/")
        }
    }

    if (extrasReady) {
        AlexaAuthMonitor(repository, snackbarHostState)
        PlayTargetLauncher(repository, playTarget, remoteOk, snackbarHostState) { playTarget = null }
    }

    BackHandler(drawerOpen) { drawerOpen = false }
    BackHandler(!drawerOpen && overlay != null) { overlay = null }

    val showingOverlay = overlay != null
    val title = titleForScreen(topRoute, overlay)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (showingOverlay) {
                            IconButton(onClick = { overlay = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        } else {
                            IconButton(onClick = { drawerOpen = true }) {
                                Icon(Icons.Default.Menu, "Menu")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (val o = overlay) {
                    is OverlayScreen.PlaylistDetail -> PlaylistDetailScreen(
                        repository,
                        o.id,
                        remoteOk,
                        onPlay = { playTarget = it },
                    ) { overlay = null }

                    is OverlayScreen.AlbumsArtist -> AlbumsScreen(
                        repository,
                        o.artist,
                        remoteOk,
                        onPlay = { playTarget = it },
                        onOpenAlbum = { album, artist ->
                            overlay = OverlayScreen.SongsAlbum(album, artist)
                        },
                    )

                    is OverlayScreen.SongsArtist -> SongsScreen(
                        repository,
                        o.artist,
                        null,
                        remoteOk,
                        onPlay = { playTarget = it },
                    )

                    is OverlayScreen.SongsAlbum -> SongsScreen(
                        repository,
                        o.artist,
                        o.album,
                        remoteOk,
                        onPlay = { playTarget = it },
                    )

                    null -> MainScreen(
                        route = topRoute,
                        repository = repository,
                        remoteOk = remoteOk,
                        snackbarHostState = snackbarHostState,
                        onPlay = { playTarget = it },
                        onChangeServer = onChangeServer,
                        onNavigateSettings = { topRoute = BockRoute.Settings.route },
                        onOpenPlaylist = { id -> overlay = OverlayScreen.PlaylistDetail(id) },
                        onOpenArtist = { artist -> overlay = OverlayScreen.AlbumsArtist(artist) },
                        onOpenAlbumSongs = { album, artist ->
                            overlay = OverlayScreen.SongsAlbum(album, artist)
                        },
                    )
                }
            }
        }

        if (drawerOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { drawerOpen = false },
                )
                ModalDrawerSheet(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 272.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            "Bock Media",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        BockLazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(top = 3.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(BockRoute.drawerRoutes, key = { it.route }) { route ->
                                CompactDrawerItem(
                                    route = route,
                                    selected = topRoute == route.route && overlay == null,
                                    onClick = {
                                        topRoute = route.route
                                        overlay = null
                                        drawerOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    route: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlay: (PlayTarget) -> Unit,
    onChangeServer: () -> Unit,
    onNavigateSettings: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbumSongs: (String, String?) -> Unit,
) {
    when (route) {
        BockRoute.Dashboard.route -> DashboardScreen(repository, remoteOk, onPlay, onNavigateSettings)
        BockRoute.NowPlaying.route -> NowPlayingScreen(repository, snackbarHostState)
        BockRoute.Rooms.route -> RoomsScreen(repository)
        BockRoute.Search.route -> SearchScreen(repository, remoteOk, onPlay)
        BockRoute.Playlists.route -> PlaylistsScreen(repository, remoteOk, onPlay, onOpenPlaylist)
        BockRoute.Artists.route -> ArtistsScreen(repository, remoteOk, onPlay, onOpenArtist)
        BockRoute.Albums.route -> AlbumsScreen(
            repository,
            null,
            remoteOk,
            onPlay,
            onOpenAlbumSongs,
        )
        BockRoute.Songs.route -> SongsScreen(repository, null, null, remoteOk, onPlay)
        BockRoute.WatchFolders.route -> WatchFoldersScreen(repository)
        BockRoute.Devices.route -> DevicesScreen(repository)
        BockRoute.Automation.route -> AutomationScreen(repository)
        BockRoute.Routines.route -> RoutinesScreen(repository)
        BockRoute.Analytics.route -> AnalyticsScreen(repository)
        BockRoute.Settings.route -> SettingsScreen(repository, onChangeServer)
        else -> DashboardScreen(repository, remoteOk, onPlay, onNavigateSettings)
    }
}

private fun titleForScreen(topRoute: String, overlay: OverlayScreen?): String = when (overlay) {
    is OverlayScreen.PlaylistDetail -> "Playlist"
    is OverlayScreen.AlbumsArtist -> "Albums · ${overlay.artist}"
    is OverlayScreen.SongsArtist -> "Songs · ${overlay.artist}"
    is OverlayScreen.SongsAlbum -> overlay.album.ifBlank { "Songs" }
    null -> BockRoute.drawerRoutes.find { it.route == topRoute }?.title ?: "Bock Media"
}

@Composable
private fun CompactDrawerItem(
    route: BockRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(40.dp),
        shape = RoundedCornerShape(7.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(route.icon, contentDescription = route.title, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(route.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}
