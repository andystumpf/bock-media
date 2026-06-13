package com.bockmedia.console.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.api.dto.PlayResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import retrofit2.HttpException

suspend fun repositoryPlay(
    repository: BockMediaRepository,
    target: PlayTarget,
    device: String,
    shuffle: Boolean,
): PlayResponse = when (target) {
    is PlayTarget.Playlist -> repository.playOnDevice(
        device, "playlist", name = target.name, id = target.id, shuffle = shuffle,
    )
    is PlayTarget.Artist -> repository.playOnDevice(
        device, "artist", name = target.name, shuffle = shuffle,
    )
    is PlayTarget.Album -> repository.playOnDevice(
        device, "album", name = target.name, artist = target.artist, shuffle = shuffle,
    )
    is PlayTarget.Song -> repository.playOnDevice(
        device, "song", name = target.title, path = target.path, shuffle = shuffle,
    )
}

private fun playErrorMessage(e: Throwable): String {
    if (e is HttpException) {
        val raw = e.response()?.errorBody()?.string()
        if (!raw.isNullOrBlank()) {
            runCatching { bockJson.decodeFromString<PlayResponse>(raw) }.getOrNull()?.error?.let { return it }
        }
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "Play failed"
}

@Composable
fun PlayTargetLauncher(
    repository: BockMediaRepository,
    target: PlayTarget?,
    remoteConfigured: Boolean,
    snackbarHostState: SnackbarHostState,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(target, remoteConfigured) {
        if (target != null && !remoteConfigured) {
            snackbarHostState.showSnackbar("Configure Alexa in Settings to play on a device")
            onClear()
        }
    }
    if (target == null || !remoteConfigured) return
    var showPicker by remember(target) { mutableStateOf(true) }
    if (showPicker) {
        DevicePickerSheet(
            repository = repository,
            onDismiss = { showPicker = false; onClear() },
            onPlay = { device, shuffle ->
                val response = repositoryPlay(repository, target, device, shuffle)
                if (response.ok) {
                    snackbarHostState.showSnackbar(
                        "Playing \"${target.label}\" on ${response.device ?: device}",
                    )
                } else {
                    snackbarHostState.showSnackbar(response.error ?: "Play failed")
                }
            },
            onPlayError = { e ->
                snackbarHostState.showSnackbar(playErrorMessage(e))
            },
        )
    }
}
