package com.bockmedia.console.ui.setup

import androidx.compose.foundation.layout.*
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

@Composable
fun SetupScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val app = remember { BockMediaApp.get(context) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(BuildConfig.DEFAULT_SERVER_URL) }
    var adminUser by remember { mutableStateOf("admin") }
    var adminPass by remember { mutableStateOf("") }
    var mobileToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bock Media", style = MaterialTheme.typography.headlineMedium)
        Text("Connect to your server", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(adminUser, { adminUser = it }, label = { Text("Admin user (if password enabled)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(adminPass, { adminPass = it }, label = { Text("Admin password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(mobileToken, { mobileToken = it }, label = { Text("Mobile API token (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (!AppPreferences.isValidUrl(url)) {
                    error = "Enter a valid http or https URL"
                    return@Button
                }
                scope.launch {
                    loading = true
                    error = null
                    app.preferences.setServerUrl(url)
                    app.preferences.setAdminCredentials(adminUser.takeIf { it.isNotBlank() }, adminPass.takeIf { it.isNotBlank() })
                    app.preferences.setMobileToken(mobileToken.takeIf { it.isNotBlank() })
                    app.invalidateApi()
                    app.repository.testConnection()
                        .onSuccess { onConnected() }
                        .onFailure {
                            error = it.message ?: "Connection failed"
                            if (it.message?.contains("403") == true) {
                                error = "API blocked on public URL — use LAN IP or VPN"
                            }
                        }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Connect")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Examples:\n• http://192.168.1.187:3001\n• VPN to same LAN IP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
