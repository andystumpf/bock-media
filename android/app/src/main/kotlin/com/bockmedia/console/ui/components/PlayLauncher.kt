package com.bockmedia.console.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.api.dto.PlayResponse
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.local.LastDeviceStore
import com.bockmedia.console.media.LOCAL_PHONE_DEVICE_ID
import com.bockmedia.console.media.LocalPlaybackController
import kotlinx.coroutines.launch
import retrofit2.HttpException

suspend fun repositoryPlay(
    repository: BockMediaRepository,
    target: PlayTarget,
    device: String,
    shuffle: Boolean,
): PlayResponse = when (target) {
    is PlayTarget.Playlist -> {
        val playlistId = target.id.ifBlank { repository.resolvePlaylistId(target.name).orEmpty() }
        repository.playOnDevice(
            device, "playlist", name = target.name, id = playlistId.ifBlank { null }, shuffle = shuffle,
        )
    }
    is PlayTarget.Artist -> repository.playOnDevice(
        device, "artist", name = target.name, shuffle = shuffle,
    )
    is PlayTarget.Album -> repository.playOnDevice(
        device, "album", name = target.name, artist = target.artist, shuffle = shuffle,
    )
    is PlayTarget.Song -> repository.playOnDevice(
        device, "song", name = target.title, path = target.path, shuffle = shuffle,
    )
    is PlayTarget.Radio -> when (target.seedKind) {
        PlayTarget.RadioSeedKind.Artist, PlayTarget.RadioSeedKind.Genre ->
            repository.playOnDevice(device, "artist", name = target.name, shuffle = shuffle)
        PlayTarget.RadioSeedKind.Song ->
            repository.playOnDevice(
                device, "song", name = target.name, path = target.path, shuffle = shuffle,
            )
    }
}

private fun playErrorMessage(e: Throwable): String {
    if (e is HttpException) {
        val raw = e.response()?.errorBody()?.string()
        if (!raw.isNullOrBlank()) {
            runCatching { bockJson.decodeFromString<PlayResponse>(raw) }.getOrNull()?.error?.let { return it }
        }
    }
    return httpErrorMessage(e, "Play failed")
}

@Composable
fun PlayTargetLauncher(
    repository: BockMediaRepository,
    target: PlayTarget?,
    remoteConfigured: Boolean,
    snackbarHostState: SnackbarHostState,
    onClear: () -> Unit,
    onPlayStarted: (deviceValue: String, deviceLabel: String?) -> Unit = { _, _ -> },
    onLocalPlayStarted: () -> Unit = {},
) {
    if (target == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember(target) { mutableStateOf(true) }
    if (showPicker) {
        DevicePickerSheet(
            repository = repository,
            playLabel = target.label,
            remoteOk = remoteConfigured,
            shuffleDefault = target.shuffleDefault,
            onDismiss = { showPicker = false; onClear() },
            onPlay = { device, shuffle, deviceLabel ->
                val response = repositoryPlay(repository, target, device, shuffle)
                if (response.ok) {
                    val label = response.device ?: deviceLabel
                    PlaybackFocus.notePlayStarted(device, label)
                    scope.launch { LastDeviceStore(context).setLastDevice(device) }
                    onPlayStarted(device, label)
                    snackbarHostState.showSnackbar(
                        "Playing \"${target.label}\" on $label",
                    )
                } else {
                    snackbarHostState.showSnackbar(response.error ?: "Play failed")
                }
            },
            onPlayOnPhone = { shuffle ->
                scope.launch {
                    LocalPlaybackController.playTarget(context, target, shuffle)
                    val err = LocalPlaybackController.state.value.error
                    if (err != null) {
                        snackbarHostState.showSnackbar(err)
                    } else {
                        PlaybackFocus.notePlayStarted(LOCAL_PHONE_DEVICE_ID, "This phone")
                        snackbarHostState.showSnackbar("Playing \"${target.label}\" on this phone")
                        showPicker = false
                        onClear()
                        onLocalPlayStarted()
                    }
                }
            },
            onPlayError = { e ->
                snackbarHostState.showSnackbar(playErrorMessage(e))
            },
        )
    }
}
