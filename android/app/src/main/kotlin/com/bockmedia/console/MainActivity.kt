package com.bockmedia.console

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bockmedia.console.media.NowPlayingNotificationManager
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.OfflineNetworkMonitor
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.HomeCachePersistence
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.LibraryCachePersistence
import com.bockmedia.console.domain.model.LibrarySessionCache
import com.bockmedia.console.domain.model.SessionDiskHydrator
import com.bockmedia.console.ui.navigation.BockApp
import com.bockmedia.console.ui.setup.SetupScreen
import com.bockmedia.console.ui.components.SplashScreen
import com.bockmedia.console.ui.components.BockImageLoader
import com.bockmedia.console.ui.theme.BockMediaTheme
import com.bockmedia.console.ui.testing.UITestSupport
import androidx.lifecycle.lifecycleScope
import com.bockmedia.console.widget.NowPlayingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException

private suspend fun retryTestConnection(app: BockMediaApp, attempts: Int = 3): Boolean {
    repeat(attempts) { attempt ->
        if (attempt > 0) {
            app.invalidateEndpoint()
            delay(800L * attempt)
        }
        if (app.repository.testConnection().isSuccess) return true
    }
    return false
}

private fun authFailureMessage(e: Throwable): String = when (e) {
    is HttpException -> when (e.code()) {
        401 -> "Authentication failed — check username, password, and mobile API token"
        403 -> "Server blocked this connection — check server URL and access settings"
        else -> "HTTP ${e.code()}"
    }
    else -> e.message ?: "Can't reach server"
}

/** After server-side credential rotation, sideload builds can recover using embedded defaults. */
private suspend fun recoverAuthFromBuild(app: BockMediaApp): Boolean {
    if (com.bockmedia.console.BuildConfig.DEFAULT_ADMIN_PASSWORD.isBlank()
        && com.bockmedia.console.BuildConfig.DEFAULT_MOBILE_API_TOKEN.isBlank()
    ) {
        return false
    }
    app.preferences.applyBuildCredentialsIfPresent()
    app.invalidateEndpoint()
    return retryTestConnection(app, 2)
}

private suspend fun syncServerAfterBoot(context: android.content.Context): Result<Unit> {
    val app = BockMediaApp.get(context)
    return runCatching {
        if (app.repository.testConnection().isFailure) error("connection failed")
        app.preferences.setHasConnected(true)
        com.bockmedia.console.data.analytics.DeviceAnalyticsReporter.reportConnect(context)
        ClientPrefsSync.pullAndApply(context)
        ClientPrefsSync.markBootPullCompleted()
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent system-bar scrims so app content draws fully behind them.
        // The default auto() applies a light navigation-bar scrim in light mode,
        // which showed as a light band over dark screens (e.g. Now Playing).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        AppForegroundState.install()
        val app = BockMediaApp.get(applicationContext)
        NetworkReachability.update(applicationContext)
        val deepRoute = intent.getStringExtra(EXTRA_ROUTE)
        val uitestUri = intent.data?.takeIf { it.scheme == "bockmedia" && it.host == "uitest" }

        setContent {
            BockMediaTheme {
                val app = remember { BockMediaApp.get(this) }
                var hasServer by remember { mutableStateOf<Boolean?>(null) }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                var autoLoginError by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    NowPlayingNotificationManager.ensureChannel(this@MainActivity)
                    uitestUri?.let { uri ->
                        if (UITestSupport.isEnabled(intent)) {
                            UITestSupport.handleUri(applicationContext, uri)
                        }
                    }
                    val app = BockMediaApp.get(applicationContext)
                    withContext(Dispatchers.IO) {
                        app.preferences.applyBuildServerUrls()
                        app.preferences.clearCredentialsIfNotRemembered()
                        app.preferences.applyBuildDefaultsIfEmpty()
                        NetworkReachability.update(applicationContext)
                        if (!NetworkReachability.onWifi) {
                            app.onCellularNetwork()
                        }
                        app.configuredEndpointUrl()?.let { app.repository.primeBaseUrl(it) }
                        SessionDiskHydrator.hydrate(applicationContext)
                    }

                    val remember = app.preferences.isRememberMeSync()
                    val wasConnected = app.preferences.hasConnectedBefore()
                    if ((remember || wasConnected) && wasConnected && app.hasServerUrl()) {
                        var ok = withContext(Dispatchers.IO) { retryTestConnection(app) }
                        if (!ok) {
                            ok = withContext(Dispatchers.IO) { recoverAuthFromBuild(app) }
                        }
                        if (ok) {
                            hasServer = true
                            val sync = withContext(Dispatchers.IO) { syncServerAfterBoot(applicationContext) }
                            if (sync.isFailure) {
                                val recovered = withContext(Dispatchers.IO) { recoverAuthFromBuild(app) }
                                    && withContext(Dispatchers.IO) { syncServerAfterBoot(applicationContext).isSuccess }
                                if (!recovered) {
                                    hasServer = false
                                    autoLoginError = authFailureMessage(sync.exceptionOrNull() ?: Exception("Sync failed"))
                                }
                            }
                        } else {
                            hasServer = false
                            autoLoginError = "Authentication failed — check username, password, and mobile API token"
                        }
                        return@LaunchedEffect
                    }

                    app.preferences.applyBuildServerUrls()
                    app.preferences.clearCredentialsIfNotRemembered()
                    app.preferences.applyBuildDefaultsIfEmpty()

                    if (remember || wasConnected) {
                        if (wasConnected && app.hasServerUrl()) {
                            launch(Dispatchers.IO) {
                                var ok = retryTestConnection(app)
                                if (!ok) ok = recoverAuthFromBuild(app)
                                withContext(Dispatchers.Main) {
                                    if (ok) {
                                        app.preferences.setHasConnected(true)
                                        hasServer = true
                                        com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                            .reportConnect(this@MainActivity)
                                    } else {
                                        hasServer = false
                                        autoLoginError = "Can't reach server — check network or server URL"
                                    }
                                }
                                if (ok) {
                                    syncServerAfterBoot(this@MainActivity)
                                }
                            }
                            return@LaunchedEffect
                        }
                        var ok = withContext(Dispatchers.IO) { retryTestConnection(app) }
                        if (!ok) ok = withContext(Dispatchers.IO) { recoverAuthFromBuild(app) }
                        if (ok) {
                            app.preferences.setHasConnected(true)
                            hasServer = true
                            com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                .reportConnect(this@MainActivity)
                            withContext(Dispatchers.IO) {
                                syncServerAfterBoot(applicationContext)
                            }
                            return@LaunchedEffect
                        }
                        withContext(Dispatchers.IO) {
                            runCatching { app.repository.testConnection() }
                        }.onFailure { e ->
                            autoLoginError = authFailureMessage(e)
                        }
                    }
                    hasServer = false
                }

                LaunchedEffect(hasServer) {
                    if (hasServer == true) {
                        OfflineNetworkMonitor.start(this@MainActivity)
                        launch(Dispatchers.IO) { OfflineDownloadManager.refresh(this@MainActivity) }
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !UITestSupport.isEnabled(intent)
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, app, hasServer) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME && hasServer == true) {
                            lifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    if (!ClientPrefsSync.shouldSkipResumePull()) {
                                        ClientPrefsSync.pullAndApply(this@MainActivity)
                                    }
                                    NowPlayingWidget.refreshSession(applicationContext)
                                }
                            }
                        }
                        if (event == Lifecycle.Event.ON_PAUSE && hasServer == true) {
                            lifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    HomeFeedCache.peek()?.let { feed ->
                                        HomeCachePersistence.save(
                                            applicationContext,
                                            feed,
                                            HomeArtworkCache.snapshotCardPaths(),
                                            HomeArtworkCache.snapshotPlaylistPaths(),
                                        )
                                    }
                                }
                            }
                        }
                        if (event == Lifecycle.Event.ON_STOP && hasServer == true) {
                            lifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    HomeFeedCache.peek()?.let { feed ->
                                        HomeCachePersistence.save(
                                            applicationContext,
                                            feed,
                                            HomeArtworkCache.snapshotCardPaths(),
                                            HomeArtworkCache.snapshotPlaylistPaths(),
                                        )
                                    }
                                    LibrarySessionCache.peek()?.let { lib ->
                                        LibraryCachePersistence.save(applicationContext, lib)
                                    }
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                when (hasServer) {
                    null -> SplashScreen()
                    false -> SetupScreen(
                        initialError = autoLoginError,
                        onConnected = { hasServer = true },
                    )
                    true -> BockApp(
                        repository = app.repository,
                        deepLinkRoute = deepRoute,
                    )
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            BockImageLoader.install(applicationContext, app)
            SessionDiskHydrator.warmHomeArtwork(applicationContext, app)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data?.takeIf { it.scheme == "bockmedia" && it.host == "uitest" }
        if (uri != null && UITestSupport.isEnabled(intent)) {
            lifecycleScope.launch { UITestSupport.handleUri(applicationContext, uri) }
            return
        }
        intent.getStringExtra(EXTRA_ROUTE)?.let { recreate() }
    }

    companion object {
        const val EXTRA_ROUTE = "route"

        fun launchIntent(context: android.content.Context, route: String? = null, uitestUri: String? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                route?.let { putExtra(EXTRA_ROUTE, it) }
                uitestUri?.let {
                    data = android.net.Uri.parse(it)
                    putExtra("UITesting", true)
                }
            }
    }
}
