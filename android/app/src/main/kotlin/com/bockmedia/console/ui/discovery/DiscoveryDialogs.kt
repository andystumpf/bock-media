package com.bockmedia.console.ui.discovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlayTarget
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
    onAcquireIdeas: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(seed.title) },
        text = {
            Column {
                TextButton(onClick = { onDismiss(); onMixMuse() }) { Text("Mix Muse playlist…") }
                TextButton(onClick = { onDismiss(); onResonanceRadio() }) { Text("Resonance radio") }
                TextButton(onClick = { onDismiss(); onResonanceMix() }) { Text("Resonance mix (save)") }
                TextButton(onClick = { onDismiss(); onAcquireIdeas() }) { Text("Music to seek out…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AcquireIdeasDialog(
    repository: BockMediaRepository,
    seed: DiscoverySeed?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf(emptyList<com.bockmedia.console.data.api.dto.AcquireSuggestion>()) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seed) {
        loading = true
        error = null
        runCatching {
            val resp = if (seed != null) {
                repository.acquireSuggest(
                    seedKind = seed.kind.name,
                    path = seed.path,
                    album = seed.album,
                    artist = seed.artist,
                    playlistId = seed.playlistId,
                )
            } else {
                repository.acquireExplore()
            }
            resp.error?.takeIf { it.isNotBlank() }?.let { error(it); return@runCatching }
            items = resp.suggestions
            note = resp.note
        }.onFailure { error = it.message ?: "Failed" }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music to seek out") },
        text = {
            when {
                loading -> CircularProgressIndicator()
                error != null -> Text(error ?: "Error")
                items.isEmpty() -> Text(note ?: "No new artists found.")
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items.size, key = { idx ->
                        items[idx].mbid ?: "${items[idx].name}-$idx"
                    }) { idx ->
                        val item = items[idx]
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(item.name)
                            if (item.reasons.isNotEmpty()) {
                                Text(item.reasons.take(2).joinToString(" · "))
                            }
                            item.musicbrainzUrl?.let { url ->
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }) { Text("MusicBrainz") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
    playDiscoveryTracksLocally(
        context,
        resp.tracks,
        resp.name ?: seed.title,
        shuffle = true,
        activeTarget = seed.toRadioPlayTarget(resp.name ?: seed.title),
    )
}

private fun DiscoverySeed.toRadioPlayTarget(displayTitle: String): PlayTarget.Radio {
    val seedKind = when (kind) {
        DiscoverySeedKind.song -> PlayTarget.RadioSeedKind.Song
        DiscoverySeedKind.album -> PlayTarget.RadioSeedKind.Artist
        DiscoverySeedKind.playlist -> PlayTarget.RadioSeedKind.Artist
    }
    val seedName = when (kind) {
        DiscoverySeedKind.song -> title
        DiscoverySeedKind.album -> artist ?: album ?: title
        DiscoverySeedKind.playlist -> title
    }
    return PlayTarget.Radio(displayTitle, seedKind, seedName, path)
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
    activeTarget: PlayTarget? = null,
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
        activeTarget = activeTarget,
    )
}
