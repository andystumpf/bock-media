package com.bockmedia.console.ui.setup

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.BuildConfig
import com.bockmedia.console.ui.components.BockProgressIndicator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun SetupScreen(
    onConnected: () -> Unit,
    initialError: String? = null,
) {
    val context = LocalContext.current
    val app = remember { BockMediaApp.get(context) }
    val scope = rememberCoroutineScope()
    var adminUser by remember { mutableStateOf(BuildConfig.DEFAULT_ADMIN_USER) }
    var adminPass by remember { mutableStateOf(BuildConfig.DEFAULT_ADMIN_PASSWORD) }
    var mobileToken by remember { mutableStateOf(BuildConfig.DEFAULT_MOBILE_API_TOKEN) }
    var rememberMe by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(initialError) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.preferences.applyBuildServerUrls()
        adminUser = app.preferences.adminUser.first()
            ?: BuildConfig.DEFAULT_ADMIN_USER
        adminPass = app.preferences.adminPass.first()
            ?: BuildConfig.DEFAULT_ADMIN_PASSWORD
        mobileToken = app.preferences.mobileToken.first()
            ?: BuildConfig.DEFAULT_MOBILE_API_TOKEN
        rememberMe = app.preferences.isRememberMeSync()
    }

    Column(
        Modifier.fillMaxSize().bockVerticalScroll().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bock Media", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in to your server", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(adminUser, { adminUser = it }, label = { Text("Username (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(adminPass, { adminPass = it }, label = { Text("Password (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(mobileToken, { mobileToken = it }, label = { Text("Mobile API token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text("Remember me", style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when {
                    mobileToken.isBlank() && (adminUser.isBlank() || adminPass.isBlank()) ->
                        error = "Enter Mobile API token (or username and password)"
                    else -> scope.launch {
                        loading = true
                        error = null
                        var connected = false
                        app.preferences.applyBuildServerUrls()
                        app.preferences.setRememberMe(rememberMe)
                        app.preferences.setAdminCredentials(
                            adminUser.trim().takeIf { it.isNotBlank() },
                            adminPass.takeIf { it.isNotBlank() },
                        )
                        app.preferences.setMobileToken(mobileToken.trim().takeIf { it.isNotBlank() })
                        app.invalidateApi()
                        app.repository.testConnection()
                            .onSuccess {
                                connected = true
                                app.preferences.setHasConnected(true)
                                com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
                                    .reportConnect(context)
                                onConnected()
                            }
                            .onFailure { e ->
                                error = when (e) {
                                    is HttpException -> when (e.code()) {
                                        401 -> "Authentication failed — check username, password, and mobile API token"
                                        403 -> "External API blocked — set mobileApi.allowExternalAccess in config.json"
                                        else -> "HTTP ${e.code()}"
                                    }
                                    else -> e.message ?: "Connection failed"
                                }
                            }
                        if (!connected) loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                BockProgressIndicator(
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Uses your home Wi‑Fi server when reachable, otherwise your external address.\n" +
                "Away from home, sign in with your Mobile API token.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
