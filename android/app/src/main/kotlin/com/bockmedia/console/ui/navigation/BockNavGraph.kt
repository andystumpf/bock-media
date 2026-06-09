package com.bockmedia.console.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.AlexaAuthMonitor
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.analytics.AnalyticsScreen
import com.bockmedia.console.ui.automation.AutomationScreen
import com.bockmedia.console.ui.components.AccountMenuButton
import com.bockmedia.console.ui.components.MiniNowPlayingBar
import com.bockmedia.console.ui.components.PlayTargetLauncher
import com.bockmedia.console.ui.devices.DevicesScreen
import com.bockmedia.console.ui.library.AlbumsScreen
import com.bockmedia.console.ui.library.ArtistsScreen
import com.bockmedia.console.ui.library.LibraryScreen
import com.bockmedia.console.ui.library.SongsScreen
import com.bockmedia.console.ui.nowplaying.NowPlayingScreen
import com.bockmedia.console.ui.nowplaying.resolveSerial
import com.bockmedia.console.ui.playlists.PlaylistDetailScreen
import com.bockmedia.console.ui.playlists.PlaylistsScreen
import com.bockmedia.console.ui.rooms.RoomsScreen
import com.bockmedia.console.ui.search.SearchScreen
import com.bockmedia.console.ui.settings.SettingsScreen
import com.bockmedia.console.ui.watchfolders.WatchFoldersScreen
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockApp(repository: BockMediaRepository, onChangeServer: () -> Unit, deepLinkRoute: String? = null) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var playTarget by remember { mutableStateOf<PlayTarget?>(null) }
    var remoteOk by remember { mutableStateOf(false) }
    var alexaDevices by remember { mutableStateOf(emptyList<com.bockmedia.console.data.api.dto.AlexaDevice>()) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val fullRoute = navBackStackEntry?.destination?.route
    val header = resolveScreenHeader(fullRoute)
    val showBottomNav = isBottomNavRoute(fullRoute)
    val showMiniBar = showBottomNav && fullRoute != BockRoute.NowPlaying.route
    val showAccount = showBottomNav

    LaunchedEffect(repository) {
        runCatching {
            remoteOk = alexaControlsAvailable(repository.alexaRemoteStatus())
        }
    }

    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let { route ->
            val dest = when {
                route.startsWith("#") -> route.removePrefix("#")
                else -> route
            }
            if (dest.isNotBlank()) navController.navigate(dest) { launchSingleTop = true }
        }
    }

    AlexaAuthMonitor(repository, snackbarHostState)

    PlayTargetLauncher(repository, playTarget, remoteOk, snackbarHostState) { playTarget = null }

    suspend fun runMiniControl(dev: NowPlayingDeviceItem, action: String) {
        val serial = resolveSerial(dev, alexaDevices) ?: return
        runCatching {
            repository.deviceControl(dev.deviceId, dev.deviceName ?: "", serial, action)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(header.title) },
                navigationIcon = {
                    if (header.showBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (showAccount) {
                        AccountMenuButton { route ->
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (showMiniBar) {
                    MiniNowPlayingBar(
                        repository = repository,
                        remoteOk = remoteOk,
                        onOpenNowPlaying = {
                            navController.navigate(BockRoute.NowPlaying.route) {
                                launchSingleTop = true
                                popUpTo(BockRoute.NowPlaying.route) { saveState = true }
                                restoreState = true
                            }
                        },
                        onControl = { dev, action ->
                            if (alexaDevices.isEmpty()) {
                                runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
                            }
                            runMiniControl(dev, action)
                        },
                    )
                }
                if (showBottomNav) {
                    NavigationBar {
                        BockRoute.bottomNavRoutes.forEach { route ->
                            val selected = fullRoute?.substringBefore("/") == route.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route.route) {
                                        launchSingleTop = true
                                        popUpTo(BockRoute.NowPlaying.route) { saveState = true }
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(route.icon, route.title) },
                                label = { Text(route.title) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        BockNavHost(
            navController = navController,
            repository = repository,
            remoteOk = remoteOk,
            snackbarHostState = snackbarHostState,
            onPlay = { playTarget = it },
            onChangeServer = onChangeServer,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun BockNavHost(
    navController: NavHostController,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlay: (PlayTarget) -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController, startDestination = BockRoute.NowPlaying.route, modifier = modifier) {
        composable(BockRoute.NowPlaying.route) { NowPlayingScreen(repository, snackbarHostState) }
        composable(BockRoute.Library.route) {
            LibraryScreen(
                repository = repository,
                onOpenArtists = { navController.navigate(BockRoute.Artists.route) },
                onOpenAlbums = { navController.navigate(BockRoute.Albums.route) },
                onOpenSongs = { navController.navigate(BockRoute.Songs.route) },
            )
        }
        composable(BockRoute.Search.route) { SearchScreen(repository, remoteOk, onPlay) }
        composable(BockRoute.Playlists.route) {
            PlaylistsScreen(repository, remoteOk, onPlay) { id ->
                navController.navigate(playlistDetailRoute(id))
            }
        }
        composable(
            ROUTE_PLAYLIST_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PlaylistDetailScreen(
                repository,
                entry.arguments?.getString("id") ?: "",
                remoteOk,
                onPlay,
            ) { navController.popBackStack() }
        }
        composable(BockRoute.Artists.route) {
            ArtistsScreen(repository, remoteOk, onPlay) { artist ->
                navController.navigate(albumsArtistRoute(artist))
            }
        }
        composable(
            ROUTE_ALBUMS_ARTIST,
            arguments = listOf(navArgument("artist") { type = NavType.StringType }),
        ) { entry ->
            val artist = URLDecoder.decode(entry.arguments?.getString("artist") ?: "", "UTF-8")
            AlbumsScreen(repository, artist, remoteOk, onPlay, { album ->
                navController.navigate(songsAlbumRoute(album))
            }, onBack = { navController.popBackStack() })
        }
        composable(BockRoute.Albums.route) {
            AlbumsScreen(repository, null, remoteOk, onPlay, { album ->
                navController.navigate(songsAlbumRoute(album))
            })
        }
        composable(
            ROUTE_SONGS_ARTIST,
            arguments = listOf(navArgument("artist") { type = NavType.StringType }),
        ) { entry ->
            val artist = URLDecoder.decode(entry.arguments?.getString("artist") ?: "", "UTF-8")
            SongsScreen(repository, artist, null, remoteOk, onPlay, onBack = { navController.popBackStack() })
        }
        composable(
            ROUTE_SONGS_ALBUM,
            arguments = listOf(navArgument("album") { type = NavType.StringType }),
        ) { entry ->
            val album = URLDecoder.decode(entry.arguments?.getString("album") ?: "", "UTF-8")
            SongsScreen(repository, null, album, remoteOk, onPlay, onBack = { navController.popBackStack() })
        }
        composable(BockRoute.Songs.route) { SongsScreen(repository, null, null, remoteOk, onPlay) }
        composable(BockRoute.WatchFolders.route) { WatchFoldersScreen(repository) }
        composable(BockRoute.Rooms.route) { RoomsScreen(repository) }
        composable(BockRoute.Devices.route) { DevicesScreen(repository) }
        composable(BockRoute.Automation.route) { AutomationScreen(repository) }
        composable(BockRoute.Analytics.route) { AnalyticsScreen(repository) }
        composable(BockRoute.Settings.route) { SettingsScreen(repository, onChangeServer) }
    }
}
