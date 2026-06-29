package com.bockmedia.console.perf

/**
 * Maps perf audit areas to the 20 known speed-improvement opportunities in the Android app.
 * See agent audit: MainActivity runBlocking, endpoint probes, triple playlist fetch, etc.
 */
enum class SpeedImprovementArea(
    val id: Int,
    val auditHint: String,
) {
    COLD_START_RUNBLOCKING(
        1,
        "MainActivity.onCreate runBlocking(coldBootFast) — show splash first, hydrate async",
    ),
    COIL_INSTALL_RUNBLOCKING(
        2,
        "BockImageLoader.install runBlocking — defer authenticated Coil until after first frame",
    ),
    CELLULAR_RUNBLOCKING(
        3,
        "onCellularNetwork runBlocking in connectivity callback — use scope.launch(IO) only",
    ),
    ENDPOINT_PROBE_MUTEX(
        4,
        "First api() waits on ServerEndpointResolver probes — prime lastGood URL, refresh in background",
    ),
    HOME_SEVEN_CALL_BURST(
        5,
        "HomeFeedLoader fires 7 parallel API calls — split critical path vs deferred work",
    ),
    TAB_PREFETCH_STORM(
        6,
        "HomeScreen warmLibrary/warmOtherTabs on launch — defer until tab opened or idle",
    ),
    TRIPLE_PLAYLIST_FETCH(
        7,
        "Home + Library + Search each fetch /playlists — shared session playlist cache",
    ),
    SEARCH_BROWSE_RELOAD(
        8,
        "SearchScreen reloads browse when cache fresh — skip until TTL/pull-to-refresh",
    ),
    AUTOMATION_RELOAD(
        9,
        "AutomationScreen reloads when cache fresh — use peek until TTL expires",
    ),
    HOME_EMPTY_RETRY(
        10,
        "Empty home sections trigger second full HomeFeedLoader.load — separate empty vs offline",
    ),
    HOME_ART_RACE(
        11,
        "Per-tile HomeArtworkResolver races batch warmArtwork — tiles peek until batch done",
    ),
    SEARCH_ART_N_PLUS_1(
        12,
        "SearchHitRow rememberArtworkUrl per row — batch-resolve visible results once",
    ),
    LIBRARY_ART_N_PLUS_1(
        13,
        "LibraryItemArt per-row LaunchedEffect — rely on LibraryArtPrefetch only",
    ),
    PLAYLIST_ROW_ART(
        14,
        "SpotifyPlaylistRow produceState per row — batch prefetchPlaylistCoverPaths once",
    ),
    UNBOUNDED_ART_PREFETCH(
        15,
        "warmArtwork unbounded — cap to visible tiles + next row (~32 URLs)",
    ),
    DUPLICATE_DISK_SAVE(
        16,
        "ON_PAUSE + ON_STOP both HomeCachePersistence.save — save on STOP or debounce",
    ),
    NOW_PLAYING_POLL(
        17,
        "MiniNowPlayingBar polls every 5s — 15–30s idle, shared poller with NowPlayingScreen",
    ),
    ALEXA_STATUS_POLL(
        18,
        "BockApp + HomeScreen redundant Alexa probes — single source, probe=false periodic",
    ),
    HOME_DOWNLOAD_RECOMPOSE(
        19,
        "OfflineDownloadManager.collectAsState at HomeScreen root — scope to tiles/sheets only",
    ),
    LIBRARY_RESORT_PREFETCH(
        20,
        "Library re-sort + redundant OfflineDownloadManager.refresh + art warm on filter — cache buckets",
    ),
}
