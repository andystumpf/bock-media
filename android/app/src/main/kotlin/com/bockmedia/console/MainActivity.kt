package com.bockmedia.console

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.bockmedia.console.ui.navigation.BockApp
import com.bockmedia.console.ui.setup.SetupScreen
import com.bockmedia.console.ui.theme.BockMediaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            BockMediaTheme {
                val app = remember { BockMediaApp.get(this) }
                var hasServer by remember { mutableStateOf<Boolean?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    hasServer = app.hasServerUrl()
                }

                when (hasServer) {
                    null -> { /* loading */ }
                    false -> SetupScreen {
                        hasServer = true
                    }
                    true -> BockApp(
                        repository = app.repository,
                        onChangeServer = {
                            scope.launch {
                                app.preferences.setServerUrl("")
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
