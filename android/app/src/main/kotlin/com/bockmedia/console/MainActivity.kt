package com.bockmedia.console

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bockmedia.console.media.NowPlayingNotificationManager
import com.bockmedia.console.local.OfflineNetworkMonitor
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.HomeCachePersistence
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.LibraryCachePersistence
import com.bockmedia.console.domain.model.LibrarySessionCache
import com.bockmedia.console.ui.navigation.BockApp
import com.bockmedia.console.ui.setup.SetupScreen
import com.bockmedia.console.ui.components.SplashScreen
import com.bockmedia.console.ui.components.BockImageLoader
import com.bockmedia.console.ui.theme.BockMediaTheme
import androidx.lifecycle.lifecycleScope
import com.bockmedia.console.widget.NowPlayingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.bockmedia.console.domain.model.SessionDiskHydrator
import retrofit2.HttpException

import kotlinx.coroutines.delay

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

private data class ColdBootState(val showApp: Boolean)

private suspend fun coldBootFast(context: android.content.Context): ColdBootState {
    val app = BockMediaApp.get(context)
    app.preferences.applyBuildServerUrls()
    app.preferences.clearCredentialsIfNotRemembered()
    app.preferences.applyBuildDefaultsIfEmpty()
    SessionDiskHydrator.hydrate(context)
    NetworkReachability.update(context)
    app.configuredEndpointUrl()?.let { app.repository.primeBaseUrl(it) }
    val remember = app.preferences.isRememberMeSync()
    val wasConnected = app.preferences.hasConnectedBefore()
    return ColdBootState(showApp = (remember || wasConnected) && wasConnected && app.hasServerUrl())
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = BockMediaApp.get(applicationContext)
        NetworkReachability.update(applicationContext)
        BockImageLoader.install(applicationContext, app)
        val coldBoot = runBlocking(Dispatchers.IO) { coldBootFast(applicationContext) }
        lifecycleScope.launch(Dispatchers.IO) {
            SessionDiskHydrator.warmHomeArtwork(applicationContext, app)
        }
        val deepRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            BockMediaTheme {
                val app = remember { BockMediaApp.get(this) }
                var hasServer by remember { mutableStateOf<Boolean?>(if (coldBoot.showApp) true else null) }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                var autoLoginError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    NowPlayingNotificationManager.ensureChannel(this@MainActivity)
                    if (coldBoot.showApp) {
                        launch(Dispatchers.IO) {
                            if (retryTestConnection(app)) {
                                app.preferences.setHasConnected(true)
                                com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                    .reportConnect(this@MainActivity)
                            }
                        }
                        return@LaunchedEffect
                    }
                    app.preferences.applyBuildServerUrls()
                    app.preferences.clearCredentialsIfNotRemembered()
                    app.preferences.applyBuildDefaultsIfEmpty()

                    val remember = app.preferences.isRememberMeSync()
                    val wasConnected = app.preferences.hasConnectedBefore()
                    if (remember || wasConnected) {
                        if (wasConnected && app.hasServerUrl()) {
                            hasServer = true
                            launch(Dispatchers.IO) {
                                if (retryTestConnection(app)) {
                                    app.preferences.setHasConnected(true)
                                    com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                        .reportConnect(this@MainActivity)
                                }
                            }
                            return@LaunchedEffect
                        }
                        if (retryTestConnection(app)) {
                            app.preferences.setHasConnected(true)
                            hasServer = true
                            com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                .reportConnect(this@MainActivity)
                            return@LaunchedEffect
                        }
                        // Server slow/down — don't force re-login if user already connected once.
                        if (wasConnected && app.hasServerUrl()) {
                            hasServer = true
                            return@LaunchedEffect
                        }
                        runCatching { app.repository.testConnection() }
                            .onFailure { e ->
                                autoLoginError = when (e) {
                                    is HttpException -> when (e.code()) {
                                        401 -> "Authentication failed — check username, password, and mobile API token"
                                        403 -> "External API blocked — set mobileApi.allowExternalAccess in config.json"
                                        else -> "HTTP ${e.code()}"
                                    }
                                    else -> e.message ?: "Saved login failed — sign in again"
                                }
                            }
                    }
                    hasServer = false
                }

                LaunchedEffect(hasServer) {
                    if (hasServer == true) {
                        OfflineNetworkMonitor.start(this@MainActivity)
                        OfflineDownloadManager.refresh(this@MainActivity)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { recreate() }
    }

    companion object {
        const val EXTRA_ROUTE = "route"

        fun launchIntent(context: android.content.Context, route: String? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                route?.let { putExtra(EXTRA_ROUTE, it) }
            }
    }
}
