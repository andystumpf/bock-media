package com.bockmedia.console.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun SetupScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val app = remember { BockMediaApp.get(context) }
    val scope = rememberCoroutineScope()
    var localUrl by remember { mutableStateOf(BuildConfig.DEFAULT_LOCAL_SERVER_URL) }
    var externalUrl by remember { mutableStateOf(BuildConfig.DEFAULT_EXTERNAL_SERVER_URL) }
    var adminUser by remember { mutableStateOf("admin") }
    var adminPass by remember { mutableStateOf("") }
    var mobileToken by remember { mutableStateOf(BuildConfig.DEFAULT_MOBILE_API_TOKEN) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bock Media", style = MaterialTheme.typography.headlineMedium)
        Text("Connect to your server", style = MaterialTheme.typography.bodyMedium)
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
        OutlinedTextField(adminUser, { adminUser = it }, label = { Text("Admin user") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(adminPass, { adminPass = it }, label = { Text("Admin password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(mobileToken, { mobileToken = it }, label = { Text("Mobile API token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val local = localUrl.trim()
                val external = externalUrl.trim()
                when {
                    local.isBlank() && external.isBlank() ->
                        error = "Enter at least a local or external URL"
                    local.isNotBlank() && !AppPreferences.isValidUrl(local) ->
                        error = "Invalid local URL"
                    external.isNotBlank() && !AppPreferences.isValidUrl(external) ->
                        error = "Invalid external URL"
                    else -> scope.launch {
                        loading = true
                        error = null
                        app.preferences.setServerUrls(
                            local = local.takeIf { it.isNotBlank() },
                            external = external.takeIf { it.isNotBlank() },
                        )
                        app.preferences.setAdminCredentials(adminUser.takeIf { it.isNotBlank() }, adminPass.takeIf { it.isNotBlank() })
                        app.preferences.setMobileToken(mobileToken.takeIf { it.isNotBlank() })
                        app.invalidateApi()
                        app.repository.testConnection()
                            .onSuccess { onConnected() }
                            .onFailure { e ->
                                error = when (e) {
                                    is HttpException -> when (e.code()) {
                                        401 -> "Authentication failed — check password and mobile API token"
                                        403 -> "External API blocked — set mobileApi.allowExternalAccess in config.json"
                                        else -> "HTTP ${e.code()}"
                                    }
                                    else -> e.message ?: "Connection failed"
                                }
                            }
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Connect")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tries local URL first (2s), then external.\n" +
                "External URL needs admin password + mobileApi token when away from home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
