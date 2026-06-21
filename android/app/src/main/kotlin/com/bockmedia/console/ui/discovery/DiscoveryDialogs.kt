package com.bockmedia.console.ui.discovery

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.media.LocalPlaybackController
import kotlinx.coroutines.launch

enum class DiscoverySeedKind { song, album, playlist }

data class DiscoverySeed(
    val kind: DiscoverySeedKind,
    val title: String,
    val path: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val playlistId: String? = null,
)

@Composable
fun MixMuseDialog(
    repository: BockMediaRepository,
    seed: DiscoverySeed?,
    onDismiss: () -> Unit,
    onPlaylistCreated: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prompt by remember {
        mutableStateOf(
            when {
                seed?.kind == DiscoverySeedKind.song -> "Songs that sound like \"${seed.title}\""
                else -> ""
            },
        )
    }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mix Muse") },
        text = {
            Column {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Describe the vibe") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        saving = true
                        runCatching {
                            val resp = if (seed != null) {
                                repository.mixMuseSimilar(
                                    seedKind = seed.kind.name,
                                    path = seed.path,
                                    album = seed.album,
                                    artist = seed.artist,
                                    playlistId = seed.playlistId,
                                    prompt = prompt,
                                    save = true,
                                )
                            } else {
                                repository.aiPlaylist(prompt, "Mix Muse", 25, true)
                            }
                            val pid = resp.playlistId ?: resp.id
                            if (!pid.isNullOrBlank()) {
                                onPlaylistCreated(pid, resp.name ?: "Mix Muse")
                            }
                            onDismiss()
                        }
                        saving = false
                    }
                },
                enabled = !saving && prompt.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DiscoveryActionsDialog(
    seed: DiscoverySeed,
    onDismiss: () -> Unit,
    onMixMuse: () -> Unit,
    onResonanceRadio: () -> Unit,
    onResonanceMix: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(seed.title) },
        text = {
            Column {
                TextButton(onClick = { onDismiss(); onMixMuse() }) { Text("Mix Muse playlist…") }
                TextButton(onClick = { onDismiss(); onResonanceRadio() }) { Text("Resonance radio") }
                TextButton(onClick = { onDismiss(); onResonanceMix() }) { Text("Resonance mix (save)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

suspend fun BockMediaRepository.runResonanceRadio(
    context: Context,
    seed: DiscoverySeed,
) {
    val resp = resonanceRadio(
        seedKind = seed.kind.name,
        path = seed.path,
        album = seed.album,
        artist = seed.artist,
        playlistId = seed.playlistId,
    )
    playDiscoveryTracksLocally(context, resp.tracks, resp.name ?: seed.title, shuffle = true)
}

suspend fun BockMediaRepository.runResonanceMix(
    seed: DiscoverySeed,
    onPlaylistCreated: (String, String) -> Unit,
) {
    val resp = resonanceMix(
        seedKind = seed.kind.name,
        path = seed.path,
        album = seed.album,
        artist = seed.artist,
        playlistId = seed.playlistId,
        save = true,
    )
    val pid = resp.playlistId ?: resp.id
    if (!pid.isNullOrBlank()) {
        onPlaylistCreated(pid, resp.name ?: "Resonance mix")
    }
}

suspend fun BockMediaRepository.playDiscoveryTracksLocally(
    context: Context,
    tracks: List<PlaylistTrack>,
    title: String,
    shuffle: Boolean = true,
) {
    val localTracks = tracks.mapNotNull { t ->
        val path = t.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LocalTrack(path = path, title = t.title ?: path, artist = t.artist, album = t.album)
    }
    if (localTracks.isEmpty()) return
    LocalPlaybackController.playTracks(
        context = context,
        tracks = localTracks,
        shuffle = shuffle,
        sourceLabel = title,
    )
}
