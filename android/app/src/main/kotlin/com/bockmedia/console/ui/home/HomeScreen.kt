package com.bockmedia.console.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.HomeFeedLoader
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.matches
import com.bockmedia.console.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onAccountNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<com.bockmedia.console.domain.model.HomeFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HomeFilter.All) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        if (feed == null) loading = true
        error = null
        runCatching {
            val fresh = HomeFeedLoader.load(repository)
            HomeFeedCache.put(fresh)
            feed = fresh
        }.onFailure { error = it.message }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        HomeFeedCache.getIfFresh()?.let { cached ->
            feed = cached
            loading = false
        }
        load()
    }

    val sections = feed?.sections.orEmpty().filter { filter.matches(it.kind) }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            loading && feed == null -> LoadingBox()
            error != null && feed == null -> ErrorText(error!!) { scope.launch { load() } }
            else -> BockLazyColumn(Modifier.fillMaxSize()) {
                item {
                    HomeHeader(
                        selected = filter,
                        onSelect = { filter = it },
                        onAccountNavigate = onAccountNavigate,
                    )
                }
                if (!remoteOk) {
                    item {
                        Text(
                            "Connect Alexa remote in Settings to play from Home.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (sections.isEmpty()) {
                    item {
                        Text(
                            "Nothing here yet — play some music and your mixes will appear.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(sections.size, key = { "${filter.name}-${sections[it].id}" }) { index ->
                        SpotifyHomeSection(
                            section = sections[index],
                            repository = repository,
                            onPlay = { card -> onPlay(card.playTarget) },
                            compactTop = index == 0,
                            artLoadKey = filter,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
