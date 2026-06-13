package com.bockmedia.console

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import coil.Coil
import coil.ImageLoader
import com.bockmedia.console.media.NowPlayingNotificationManager
import com.bockmedia.console.ui.navigation.BockApp
import com.bockmedia.console.ui.setup.SetupScreen
import com.bockmedia.console.ui.theme.BockMediaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            BockMediaTheme {
                val app = remember { BockMediaApp.get(this) }
                var hasServer by remember { mutableStateOf<Boolean?>(null) }
                var setupError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                LaunchedEffect(Unit) {
                    try {
                        NowPlayingNotificationManager.ensureChannel(this@MainActivity)
                        app.preferences.clearCredentialsIfNotRemembered()
                        // Only skip setup when the user previously signed in with Remember me.
                        if (app.preferences.isRememberMeSync() && app.hasServerUrl()) {
                            val pass = app.preferences.adminPass.first()
                            val token = app.preferences.mobileToken.first()
                            if (!pass.isNullOrBlank() || !token.isNullOrBlank()) {
                                hasServer = app.repository.testConnection()
                                    .onFailure { setupError = it.message }
                                    .isSuccess
                                return@LaunchedEffect
                            }
                        }
                        hasServer = false
                    } catch (e: Exception) {
                        setupError = e.message ?: "Startup failed"
                        hasServer = false
                    }
                }

                LaunchedEffect(hasServer) {
                    if (hasServer != true) return@LaunchedEffect
                    kotlinx.coroutines.delay(800)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    runCatching {
                        val client = app.buildAuthenticatedHttpClient()
                        Coil.setImageLoader(
                            ImageLoader.Builder(this@MainActivity)
                                .okHttpClient(client)
                                .build(),
                        )
                    }
                }

                when (hasServer) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    false -> SetupScreen(
                        onConnected = {
                            setupError = null
                            hasServer = true
                        },
                        initialError = setupError,
                    )
                    true -> BockApp(
                        repository = app.repository,
                        onChangeServer = {
                            scope.launch {
                                app.preferences.clearServerUrls()
                                app.invalidateApi()
                                hasServer = false
                            }
                        },
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
