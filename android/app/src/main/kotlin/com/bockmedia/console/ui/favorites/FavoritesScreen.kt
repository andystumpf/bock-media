package com.bockmedia.console.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.RatingItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.RatedSongPlaylists
import com.bockmedia.console.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onPlayLocal: (PlayTarget) -> Unit = onPlay,
    onOpenPlaylist: (String) -> Unit = {},
    onBrowseSearch: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RatingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        if (items.isEmpty()) loading = true
        runCatching { items = repository.ratedSongs() }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }

    val byStar = remember(items) {
        items.filter { it.kind == "song" && it.stars in 1..5 }.groupBy { it.stars }
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            loading -> LoadingBox()
            byStar.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Rate tracks in Now Playing or Search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBrowseSearch) { Text("Browse Search") }
                    OutlinedButton(onClick = onOpenLibrary) { Text("Open Library") }
                }
            }
            else -> BockLazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                item {
                    Text(
                        "Play all tracks at each rating level.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                for (stars in RatedSongPlaylists.starLevelsDescending) {
                    val songs = byStar[stars].orEmpty()
                    if (songs.isEmpty()) continue
                    val target = RatedSongPlaylists.playTarget(stars)
                    item(key = "rated-$stars") {
                        LibraryArtListItem(
                            repository = repository,
                            title = RatedSongPlaylists.title(stars),
                            subtitle = "${songs.size} tracks · Tap to browse",
                            artPath = songs.firstOrNull()?.id,
                            modifier = Modifier.clickable { onOpenPlaylist(RatedSongPlaylists.id(stars)) },
                            trailing = {
                                PlayButton(
                                    onClick = {
                                        if (remoteOk) onPlay(target)
                                        else scope.launch {
                                            PhonePlayback.playLocally(context, target)
                                        }
                                    },
                                    enabled = remoteOk || true,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
