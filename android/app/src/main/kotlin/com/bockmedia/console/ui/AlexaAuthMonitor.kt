package com.bockmedia.console.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import com.bockmedia.console.data.api.dto.AlexaRemoteStatus
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.delay

@Composable
fun AlexaAuthMonitor(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState,
) {
    var lastAuth by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(repository) {
        while (true) {
            val st = runCatching { repository.alexaRemoteStatus() }.getOrNull()
            if (st != null) {
                if (st.configured && st.authenticated == false && lastAuth == true) {
                    snackbarHostState.showSnackbar(
                        "Alexa session expired — re-login in Settings",
                        withDismissAction = true,
                    )
                }
                lastAuth = st.authenticated
            } else if (lastAuth == true) {
                snackbarHostState.showSnackbar(
                    "Cannot reach server — Alexa status unknown",
                    withDismissAction = true,
                )
                lastAuth = null
            }
            delay(120_000)
        }
    }
}

@Composable
fun rememberAlexaRemoteStatus(repository: BockMediaRepository): State<AlexaRemoteStatus?> {
    val state = remember { mutableStateOf<AlexaRemoteStatus?>(null) }
    LaunchedEffect(repository) {
        runCatching { state.value = repository.alexaRemoteStatus(probe = true) }
    }
    return state
}

fun alexaControlsAvailable(status: AlexaRemoteStatus?): Boolean =
    status?.configured == true && status.authenticated == true

fun AlexaRemoteStatus.effectiveLoginStatus(): String? =
    loginStatus?.takeIf { it.isNotBlank() } ?: status?.takeIf { it.isNotBlank() }

fun AlexaRemoteStatus.effectiveLoginUrl(): String? {
    loginUrl?.takeIf { it.isNotBlank() }?.let { return it }
    url?.takeIf { it.isNotBlank() }?.let { return it }
    val h = loginProxyHost?.takeIf { it.isNotBlank() } ?: host?.takeIf { it.isNotBlank() }
    val p = loginProxyPort ?: port
    return if (!h.isNullOrBlank() && p != null) "http://$h:$p" else null
}

fun AlexaRemoteStatus.effectiveLoginError(): String? =
    loginError?.takeIf { it.isNotBlank() } ?: error?.takeIf { it.isNotBlank() }

fun alexaRemotePlayMessage(status: AlexaRemoteStatus?): String? {
    if (status == null) {
        return "Can't reach server for Alexa status. Check your connection in Settings."
    }
    if (!status.configured) {
        return "Alexa remote isn't configured on the server (alexaRemote in config.json)."
    }
    if (status.authenticated != true) {
        return "Sign in to Alexa in Settings → Start browser login. Use home Wi‑Fi or forward port 3005."
    }
    return null
}

suspend fun refreshAlexaControlsAvailable(repository: BockMediaRepository): Boolean =
    runCatching { alexaControlsAvailable(repository.alexaRemoteStatus(probe = true)) }.getOrDefault(false)
