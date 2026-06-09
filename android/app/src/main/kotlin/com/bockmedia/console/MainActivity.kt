package com.bockmedia.console

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bockmedia.console.media.NowPlayingNotificationManager
import com.bockmedia.console.local.OfflineNetworkMonitor
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.ui.navigation.BockApp
import com.bockmedia.console.ui.setup.SetupScreen
import com.bockmedia.console.ui.components.SplashScreen
import com.bockmedia.console.ui.theme.BockMediaTheme
import androidx.lifecycle.lifecycleScope
import com.bockmedia.console.widget.NowPlayingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            BockMediaTheme {
                val app = remember { BockMediaApp.get(this) }
                var hasServer by remember { mutableStateOf<Boolean?>(null) }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                LaunchedEffect(Unit) {
                    NowPlayingNotificationManager.ensureChannel(this@MainActivity)
                    app.preferences.applyBuildServerUrls()
                    app.preferences.clearCredentialsIfNotRemembered()
                    if (app.preferences.isRememberMeSync()) {
                        app.preferences.applyBuildDefaultsIfEmpty()
                        runCatching { app.repository.testConnection() }
                            .onSuccess { hasServer = true; return@LaunchedEffect }
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
                        runCatching {
                            val client = app.buildAuthenticatedHttpClient()
                            Coil.setImageLoader(
                                ImageLoader.Builder(this@MainActivity)
                                    .okHttpClient(client)
                                    .memoryCache {
                                        MemoryCache.Builder(this@MainActivity)
                                            .maxSizePercent(0.25)
                                            .build()
                                    }
                                    .diskCache {
                                        DiskCache.Builder()
                                            .directory(cacheDir.resolve("image_cache"))
                                            .maxSizePercent(0.05)
                                            .build()
                                    }
                                    .crossfade(true)
                                    .build(),
                            )
                        }
                    }
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, app, hasServer) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME && hasServer == true) {
                            app.invalidateApi()
                            lifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    NowPlayingWidget.refreshSession(applicationContext)
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                when (hasServer) {
                    null -> SplashScreen()
                    false -> SetupScreen {
                        hasServer = true
                    }
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
