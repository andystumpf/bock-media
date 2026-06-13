package com.bockmedia.console.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.local.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import retrofit2.HttpException

@Composable
fun SetupScreen(onConnected: () -> Unit, initialError: String? = null) {
    val context = LocalContext.current
    val app = remember { BockMediaApp.get(context) }
    val scope = rememberCoroutineScope()
    var localUrl by remember { mutableStateOf(BuildConfig.DEFAULT_LOCAL_SERVER_URL) }
    var externalUrl by remember { mutableStateOf(BuildConfig.DEFAULT_EXTERNAL_SERVER_URL) }
    var adminUser by remember { mutableStateOf(BuildConfig.DEFAULT_ADMIN_USER) }
    var adminPass by remember { mutableStateOf(BuildConfig.DEFAULT_ADMIN_PASSWORD) }
    var mobileToken by remember { mutableStateOf(BuildConfig.DEFAULT_MOBILE_API_TOKEN) }
    var rememberMe by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(initialError) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        localUrl = app.preferences.getLocalServerUrlSync()
            ?: BuildConfig.DEFAULT_LOCAL_SERVER_URL
        externalUrl = app.preferences.getExternalServerUrlSync()
            ?: BuildConfig.DEFAULT_EXTERNAL_SERVER_URL
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
        OutlinedTextField(
            localUrl,
            { localUrl = it },
            label = { Text("Local URL (Wi‑Fi / LAN)") },
            placeholder = { Text("http://192.168.1.187:3001") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            externalUrl,
            { externalUrl = it },
            label = { Text("External URL (cellular / away)") },
            placeholder = { Text("http://142.56.8.193:3001") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            mobileToken,
            { mobileToken = it },
            label = { Text("Mobile API token (required away from home)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            adminUser,
            { adminUser = it },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            adminPass,
            { adminPass = it },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
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
                val local = localUrl.trim()
                val external = externalUrl.trim()
                val token = mobileToken.trim()
                val user = adminUser.trim()
                val pass = adminPass.trim()
                when {
                    local.isBlank() && external.isBlank() ->
                        error = "Enter at least a local or external URL"
                    local.isNotBlank() && !AppPreferences.isValidUrl(local) ->
                        error = "Invalid local URL"
                    external.isNotBlank() && !AppPreferences.isValidUrl(external) ->
                        error = "Invalid external URL"
                    external.isNotBlank() && token.isBlank() && (user.isBlank() || pass.isBlank()) ->
                        error = "Mobile API token required for external URL (or username and password)"
                    else -> scope.launch {
                        loading = true
                        error = null
                        var connected = false
                        try {
                            app.preferences.setRememberMe(rememberMe)
                            app.preferences.setServerUrls(
                                local = local.takeIf { it.isNotBlank() },
                                external = external.takeIf { it.isNotBlank() },
                            )
                            app.preferences.setAdminCredentials(
                                user.takeIf { it.isNotBlank() },
                                pass.takeIf { it.isNotBlank() },
                            )
                            app.preferences.setMobileToken(token.takeIf { it.isNotBlank() })
                            app.invalidateApi()
                            val result = app.repository.testConnection()
                            if (result.isSuccess) {
                                connected = true
                                // Let setup screen dispose before swapping to main UI.
                                delay(100)
                                onConnected()
                            } else {
                                error = result.exceptionOrNull().let { e ->
                                    when (e) {
                                        is HttpException -> when (e.code()) {
                                            401 -> "Authentication failed — check Mobile API token"
                                            403 -> "External API blocked — set mobileApi.allowExternalAccess in config.json"
                                            else -> "HTTP ${e.code()}"
                                        }
                                        else -> e?.message ?: "Connection failed"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Sign in failed"
                        }
                        if (!connected) loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Sign in")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Away from home: external URL + Mobile API token only.\n" +
                "Leave username and password blank — they are not used.\n" +
                "On Wi‑Fi: local URL works with no credentials.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
