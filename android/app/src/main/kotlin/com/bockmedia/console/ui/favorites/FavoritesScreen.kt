package com.bockmedia.console.ui.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.RatingItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.components.RatingKind
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onPlayLocal: (PlayTarget) -> Unit = onPlay,
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

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            loading -> LoadingBox()
            items.isEmpty() -> Column(
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
                items(items, key = { it.id }) { rated ->
                    val target = PlayTarget.Song(rated.id, rated.title ?: "Track")
                    var stars by remember(rated.id) { mutableIntStateOf(rated.stars) }
                    LibraryArtListItem(
                        repository = repository,
                        title = rated.title ?: "Track",
                        subtitle = listOfNotNull(rated.artist, rated.album).joinToString(" · "),
                        artPath = rated.id,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactStarRatingBar(
                                    stars = stars,
                                    tint = MaterialTheme.colorScheme.primary,
                                    onStarsChange = { value ->
                                        stars = value
                                        scope.launch {
                                            runCatching {
                                                repository.setRating(
                                                    kind = RatingKind.Song,
                                                    id = rated.id,
                                                    stars = value,
                                                    title = rated.title,
                                                    artist = rated.artist,
                                                    album = rated.album,
                                                )
                                            }
                                            load()
                                        }
                                    },
                                )
                                PlayButton(
                                    onClick = {
                                        if (remoteOk) onPlay(target)
                                        else scope.launch {
                                            PhonePlayback.playLocally(context, target)
                                        }
                                    },
                                    enabled = remoteOk || true,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
