package com.bockmedia.console.ui.discovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.ui.theme.BockGreen
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

private val TrackCountOptions = listOf(10, 15, 20, 25, 30, 40)

@Composable
fun MixMuseDialog(
    repository: BockMediaRepository,
    seed: DiscoverySeed?,
    onDismiss: () -> Unit,
    onPlaylistCreated: (String, String) -> Unit,
    title: String = "Mix Muse",
    defaultName: String = title,
    defaultTrackCount: Int = 20,
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
    var trackCount by remember { mutableIntStateOf(defaultTrackCount) }
    var mode by remember { mutableStateOf<String?>(null) }
    var loadingStatus by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loadingStatus = true
        runCatching { repository.mixMuseStatus() }
            .onSuccess { mode = it.mode ?: it.provider }
            .onFailure { mode = "local" }
        loadingStatus = false
    }

    val modeLabel = when (mode?.lowercase()) {
        "claude" -> "Claude AI"
        "openai" -> "OpenAI"
        "local" -> "Local · Picard + MusicBrainz"
        else -> if (loadingStatus) "Checking…" else "Auto"
    }

    suspend fun createPlaylist(): Result<Pair<String, String>> = runCatching {
        val resp = if (seed != null) {
            repository.mixMuseSimilar(
                seedKind = seed.kind.name,
                path = seed.path,
                album = seed.album,
                artist = seed.artist,
                playlistId = seed.playlistId,
                prompt = prompt,
                save = true,
                maxTracks = trackCount,
            )
        } else {
            repository.mixMusePlaylist(
                prompt = prompt,
                name = defaultName,
                maxTracks = trackCount,
                save = true,
            )
        }
        val pid = resp.playlistId ?: resp.id
        val name = resp.name ?: defaultName
        if (pid.isNullOrBlank()) error("No playlist returned")
        pid to name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    modeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = BockGreen,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Describe the vibe") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working,
                )
                Text("How many songs?", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackCountOptions.forEach { n ->
                        FilterChip(
                            selected = trackCount == n,
                            onClick = { trackCount = n },
                            label = { Text("$n") },
                            enabled = !working,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        working = true
                        error = null
                        createPlaylist()
                            .onSuccess { (pid, name) ->
                                onPlaylistCreated(pid, name)
                                onDismiss()
                            }
                            .onFailure { error = httpErrorMessage(it, "Create failed") }
                        working = false
                    }
                },
                enabled = !working && prompt.isNotBlank(),
            ) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Create")
            }
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
