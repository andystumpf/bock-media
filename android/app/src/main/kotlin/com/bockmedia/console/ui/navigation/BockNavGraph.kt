package com.bockmedia.console.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import kotlinx.coroutines.launch
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockApp(repository: BockMediaRepository, onChangeServer: () -> Unit, deepLinkRoute: String? = null) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var playTarget by remember { mutableStateOf<PlayTarget?>(null) }
    var remoteOk by remember { mutableStateOf(false) }

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

    PlayTargetLauncher(repository, playTarget, remoteOk) { playTarget = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Bock Media", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                BockRoute.drawerRoutes.forEach { route ->
                    NavigationDrawerItem(
                        label = { Text(route.title) },
                        icon = { Icon(route.icon, null) },
                        selected = currentRoute(navController) == route.route,
                        onClick = {
                            navController.navigate(route.route) { launchSingleTop = true }
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(titleForRoute(currentRoute(navController))) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                )
            },
        ) { padding ->
            BockNavHost(
                navController = navController,
                repository = repository,
                remoteOk = remoteOk,
                onPlay = { playTarget = it },
                onChangeServer = onChangeServer,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun BockNavHost(
    navController: NavHostController,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController, startDestination = BockRoute.Dashboard.route, modifier = modifier) {
        composable(BockRoute.Dashboard.route) {
            DashboardScreen(repository, onPlay) { navController.navigate(BockRoute.Settings.route) }
        }
        composable(BockRoute.NowPlaying.route) { NowPlayingScreen(repository) }
        composable(BockRoute.Rooms.route) { RoomsScreen(repository) }
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
        composable(BockRoute.Devices.route) { DevicesScreen(repository) }
        composable(BockRoute.Automation.route) { AutomationScreen(repository) }
        composable(BockRoute.Routines.route) { RoutinesScreen(repository) }
        composable(BockRoute.Analytics.route) { AnalyticsScreen(repository) }
        composable(BockRoute.Settings.route) { SettingsScreen(repository, onChangeServer) }
    }
}

private fun currentRoute(navController: NavHostController): String? {
    val entry = navController.currentBackStackEntryAsState().value ?: return null
    return entry.destination.route?.substringBefore("/")
}

private fun titleForRoute(route: String?): String =
    BockRoute.drawerRoutes.find { it.route == route }?.title ?: "Bock Media"
