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
            runCatching {
                val st = repository.alexaRemoteStatus()
                if (st.configured && st.authenticated == false && lastAuth != false) {
                    snackbarHostState.showSnackbar(
                        "Alexa session expired — re-login in Settings",
                        withDismissAction = true,
                    )
                }
                lastAuth = st.authenticated
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
