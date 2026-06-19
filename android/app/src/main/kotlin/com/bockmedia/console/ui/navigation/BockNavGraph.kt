package com.bockmedia.console.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
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
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.ui.AlexaAuthMonitor
import com.bockmedia.console.ui.refreshAlexaControlsAvailable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bockmedia.console.ui.analytics.AnalyticsScreen
import com.bockmedia.console.ui.automation.AutomationScreen
import com.bockmedia.console.ui.components.MiniNowPlayingBar
import com.bockmedia.console.ui.components.PlayTargetLauncher
import com.bockmedia.console.ui.downloads.DownloadsScreen
import com.bockmedia.console.ui.favorites.FavoritesScreen
import com.bockmedia.console.ui.recent.RecentRequestsScreen
import com.bockmedia.console.ui.routines.RoutinesScreen
import com.bockmedia.console.ui.devices.DevicesScreen
import com.bockmedia.console.ui.home.HomeScreen
import com.bockmedia.console.ui.library.AlbumsScreen
import com.bockmedia.console.ui.library.ArtistsScreen
import com.bockmedia.console.ui.library.LibraryScreen
import com.bockmedia.console.ui.library.SongsScreen
import com.bockmedia.console.ui.nowplaying.NowPlayingScreen
import com.bockmedia.console.ui.nowplaying.resolveSerial
import com.bockmedia.console.ui.playlists.PlaylistDetailScreen
import com.bockmedia.console.ui.playlists.PlaylistsScreen
import com.bockmedia.console.ui.rooms.RoomsScreen
import com.bockmedia.console.ui.search.GenreDetailScreen
import com.bockmedia.console.ui.search.SearchScreen
import com.bockmedia.console.ui.settings.SettingsScreen
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockApp(repository: BockMediaRepository, deepLinkRoute: String? = null) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var playTarget by remember { mutableStateOf<PlayTarget?>(null) }
    var playbackFocusGeneration by remember { mutableIntStateOf(PlaybackFocus.generation) }
    var remoteOk by remember { mutableStateOf(false) }
    var alexaDevices by remember { mutableStateOf(emptyList<com.bockmedia.console.data.api.dto.AlexaDevice>()) }
    val scope = rememberCoroutineScope()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val fullRoute = navBackStackEntry?.destination?.route
    val header = resolveScreenHeader(navBackStackEntry)
    val showBottomNav = isBottomNavRoute(fullRoute)
    val showMiniBar = showBottomNav
    val isBottomNavRoot = isBottomNavRoot(fullRoute)
    val isNowPlaying = fullRoute?.substringBefore("/") == BockRoute.NowPlaying.route

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(repository) {
        while (true) {
            remoteOk = refreshAlexaControlsAvailable(repository)
            delay(60_000)
        }
    }
    DisposableEffect(lifecycleOwner, repository, scope) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { remoteOk = refreshAlexaControlsAvailable(repository) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(deepLinkRoute) {
        val dest = deepLinkRoute?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // The NavHost installs its graph during its own composition, which can land
        // after this effect runs. Navigating before the graph exists throws and crashes
        // the app (killing any in-progress work), so wait until the start destination is
        // on the back stack, then navigate defensively.
        snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
        runCatching { navController.navigate(dest) { launchSingleTop = true } }
    }

    AlexaAuthMonitor(repository, snackbarHostState)

    PlayTargetLauncher(
        repository,
        playTarget,
        remoteOk,
        snackbarHostState,
        onClear = { playTarget = null },
        onPlayStarted = { _, _ -> playbackFocusGeneration = PlaybackFocus.generation },
    )

    suspend fun runMiniControl(dev: NowPlayingDeviceItem, action: String) {
        val serial = resolveSerial(dev, alexaDevices) ?: return
        runCatching {
            repository.deviceControl(dev.deviceId, dev.deviceName ?: "", serial, action)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!isNowPlaying && !isBottomNavRoot) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = { Text(header.title) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    navigationIcon = {
                        if (header.showBack) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showBottomNav) {
                NavigationBar(
                    modifier = Modifier.testTag(BockTestTags.BOTTOM_NAV),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    BockRoute.bottomNavRoutes.forEach { route ->
                        val selected = fullRoute?.substringBefore("/") == route.route
                        val navTag = when (route) {
                            BockRoute.Home -> BockTestTags.NAV_HOME
                            BockRoute.Search -> BockTestTags.NAV_SEARCH
                            BockRoute.Library -> BockTestTags.NAV_LIBRARY
                            BockRoute.Automations -> BockTestTags.NAV_AUTOMATIONS
                            else -> "bock_nav_${route.route}"
                        }
                        NavigationBarItem(
                            modifier = Modifier.testTag(navTag),
                            selected = selected,
                            onClick = {
                                navController.navigate(route.route) {
                                    launchSingleTop = true
                                    popUpTo(BockRoute.Home.route) { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(route.icon, route.title) },
                            label = { Text(route.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = when {
                isNowPlaying -> Modifier.fillMaxSize()
                isBottomNavRoot -> Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                else -> Modifier
                    .padding(padding)
                    .fillMaxSize()
            },
        ) {
            BockNavHost(
                navController = navController,
                repository = repository,
                remoteOk = remoteOk,
                snackbarHostState = snackbarHostState,
                onPlay = { playTarget = it },
                playbackFocusGeneration = playbackFocusGeneration,
                modifier = Modifier.fillMaxSize(),
            )
            if (showMiniBar) {
                MiniNowPlayingBar(
                    repository = repository,
                    remoteOk = remoteOk,
                    playbackFocusGeneration = playbackFocusGeneration,
                    onOpenNowPlaying = {
                        navController.navigate(BockRoute.NowPlaying.route) {
                            launchSingleTop = true
                        }
                    },
                    onControl = { dev, action ->
                        if (alexaDevices.isEmpty()) {
                            runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
                        }
                        runMiniControl(dev, action)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BockNavHost(
    navController: NavHostController,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlay: (PlayTarget) -> Unit,
    playbackFocusGeneration: Int = 0,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController,
        startDestination = BockRoute.Home.route,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(BockRoute.Home.route) {
            HomeScreen(
                repository = repository,
                remoteOk = remoteOk,
                onPlay = onPlay,
                onAccountNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
                onOpenDownloads = {
                    navController.navigate(BockRoute.Downloads.route) { launchSingleTop = true }
                },
                onOpenPlaylist = { id ->
                    navController.navigate(playlistDetailRoute(id)) { launchSingleTop = true }
                },
            )
        }
        composable(BockRoute.NowPlaying.route) {
            NowPlayingScreen(
                repository = repository,
                snackbarHostState = snackbarHostState,
                onBack = { navController.popBackStack() },
                playbackFocusGeneration = playbackFocusGeneration,
            )
        }
        composable(BockRoute.Library.route) {
            LibraryScreen(
                repository = repository,
                remoteOk = remoteOk,
                onPlay = onPlay,
                onOpenPlaylist = { id -> navController.navigate(playlistDetailRoute(id)) },
                onOpenArtist = { name -> navController.navigate(albumsArtistRoute(name)) },
                onOpenAlbum = { name -> navController.navigate("songs/album/${android.net.Uri.encode(name)}") },
                onOpenFavorites = { navController.navigate(BockRoute.Favorites.route) },
                onOpenPlaylists = { navController.navigate(BockRoute.Playlists.route) },
                onAccountNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        }
        composable(BockRoute.Favorites.route) {
            FavoritesScreen(
                repository = repository,
                remoteOk = remoteOk,
                onPlay = onPlay,
                onBrowseSearch = { navController.navigate(BockRoute.Search.route) },
                onOpenLibrary = { navController.navigate(BockRoute.Library.route) },
            )
        }
        composable(BockRoute.Downloads.route) {
            DownloadsScreen(
                onOpenPlaylist = { id ->
                    navController.navigate(playlistDetailRoute(id))
                },
                snackbarHostState = snackbarHostState,
                onOpenNowPlaying = {
                    navController.navigate(BockRoute.NowPlaying.route) { launchSingleTop = true }
                },
            )
        }
        composable(BockRoute.Routines.route) { RoutinesScreen(repository) }
        composable(BockRoute.RecentRequests.route) { RecentRequestsScreen(repository) }
        composable(BockRoute.Search.route) {
            SearchScreen(
                repository,
                remoteOk,
                onPlay,
                onOpenArtist = { navController.navigate(albumsArtistRoute(it)) },
                onOpenAlbum = { album, _ -> navController.navigate(songsAlbumRoute(album)) },
                onOpenGenre = { name -> navController.navigate(genreRoute(name)) },
                onOpenPlaylist = { id ->
                    navController.navigate(playlistDetailRoute(id)) { launchSingleTop = true }
                },
                onAccountNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
                snackbarHostState = snackbarHostState,
            )
        }
        composable(
            ROUTE_GENRE,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val genreName = URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
            GenreDetailScreen(
                genreName = genreName,
                repository = repository,
                remoteOk = remoteOk,
                onPlay = onPlay,
                onOpenArtist = { navController.navigate(albumsArtistRoute(it)) },
                onOpenAlbum = { album, artist -> navController.navigate(songsAlbumRoute(album)) },
            )
        }
        composable(BockRoute.Automations.route) {
            AutomationScreen(
                repository = repository,
                onAccountNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        }
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
            })
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
            SongsScreen(repository, artist, null, remoteOk, onPlay)
        }
        composable(
            ROUTE_SONGS_ALBUM,
            arguments = listOf(navArgument("album") { type = NavType.StringType }),
        ) { entry ->
            val album = URLDecoder.decode(entry.arguments?.getString("album") ?: "", "UTF-8")
            SongsScreen(repository, null, album, remoteOk, onPlay)
        }
        composable(BockRoute.Songs.route) { SongsScreen(repository, null, null, remoteOk, onPlay) }
        composable(BockRoute.Rooms.route) {
            RoomsScreen(
                repository = repository,
                remoteOk = remoteOk,
                onPlay = onPlay,
                onOpenNowPlaying = {
                    navController.navigate(BockRoute.NowPlaying.route) { launchSingleTop = true }
                },
            )
        }
        composable(BockRoute.Devices.route) { DevicesScreen(repository) }
        composable(BockRoute.Analytics.route) { AnalyticsScreen(repository) }
        composable(BockRoute.Settings.route) {
            SettingsScreen(
                repository = repository,
                snackbarHostState = snackbarHostState,
                onOpenNowPlaying = {
                    navController.navigate(BockRoute.NowPlaying.route) { launchSingleTop = true }
                },
                onOpenPlaylist = { id ->
                    navController.navigate(playlistDetailRoute(id))
                },
                onOpenPlaylists = { navController.navigate(BockRoute.Playlists.route) },
            )
        }
    }
}
