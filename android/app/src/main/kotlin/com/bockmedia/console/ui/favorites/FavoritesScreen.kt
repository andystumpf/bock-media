package com.bockmedia.console.ui.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
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
    var items by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        if (items.isEmpty()) loading = true
        runCatching { items = repository.favorites() }
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
                    "Like tracks in Now Playing or Search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBrowseSearch) { Text("Browse Search") }
                    OutlinedButton(onClick = onOpenLibrary) { Text("Open Library") }
                }
            }
            else -> BockLazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(items, key = { it.path }) { fav ->
                    val target = PlayTarget.Song(fav.path, fav.track ?: "Favorite")
                    LibraryArtListItem(
                        repository = repository,
                        title = fav.track ?: "Favorite",
                        subtitle = listOfNotNull(fav.artist, fav.album).joinToString(" · "),
                        artPath = fav.path,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    scope.launch {
                                        repository.removeFavorite(fav.path)
                                        load()
                                    }
                                }) {
                                    Icon(Icons.Default.Star, contentDescription = "Unstar", tint = MaterialTheme.colorScheme.primary)
                                }
                                PlayButton(
                                    onClick = {
                                        if (remoteOk) onPlay(target)
                                        else scope.launch {
                                            val err = PhonePlayback.playLocally(context, target)
                                            err?.let { /* silent or could show snackbar */ }
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
