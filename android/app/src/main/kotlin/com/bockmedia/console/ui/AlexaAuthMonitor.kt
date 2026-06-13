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
        runCatching { state.value = repository.alexaRemoteStatus() }
    }
    return state
}

fun alexaControlsAvailable(status: AlexaRemoteStatus?): Boolean =
    status?.configured == true && status.authenticated == true

suspend fun refreshAlexaControlsAvailable(repository: BockMediaRepository): Boolean =
    runCatching { alexaControlsAvailable(repository.alexaRemoteStatus()) }.getOrDefault(false)
