package com.bockmedia.console.ui.components

import androidx.compose.runtime.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget

suspend fun repositoryPlay(
    repository: BockMediaRepository,
    target: PlayTarget,
    device: String,
    shuffle: Boolean,
) {
    when (target) {
        is PlayTarget.Playlist -> repository.playOnDevice(device, "playlist", name = target.name, id = target.id, shuffle = shuffle)
        is PlayTarget.Artist -> repository.playOnDevice(device, "artist", name = target.name, shuffle = shuffle)
        is PlayTarget.Album -> repository.playOnDevice(device, "album", name = target.name, shuffle = shuffle)
        is PlayTarget.Song -> repository.playOnDevice(device, "song", name = target.path, shuffle = shuffle)
    }
}

@Composable
fun PlayTargetLauncher(
    repository: BockMediaRepository,
    target: PlayTarget?,
    remoteConfigured: Boolean,
    onClear: () -> Unit,
) {
    if (target == null || !remoteConfigured) return
    var showPicker by remember(target) { mutableStateOf(true) }
    if (showPicker) {
        DevicePickerSheet(
            repository = repository,
            onDismiss = { showPicker = false; onClear() },
            onPlay = { device, shuffle ->
                repositoryPlay(repository, target, device, shuffle)
            },
        )
    }
}
