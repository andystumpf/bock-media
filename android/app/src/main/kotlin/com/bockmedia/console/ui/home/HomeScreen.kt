package com.bockmedia.console.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.HomeFeedLoader
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.buildOfflineHomeSection
import com.bockmedia.console.domain.model.matches
import com.bockmedia.console.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<com.bockmedia.console.domain.model.HomeFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HomeFilter.All) }
    var error by remember { mutableStateOf<String?>(null) }
    var offlineSection by remember { mutableStateOf<com.bockmedia.console.domain.model.HomeSection?>(null) }

    suspend fun loadOffline() {
        offlineSection = buildOfflineHomeSection(context)
    }

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
        OfflineDownloadManager.refresh(context)
        HomeFeedCache.getIfFresh()?.let { cached ->
            feed = cached
            loading = false
        }
        load()
        loadOffline()
    }

    LaunchedEffect(filter) {
        if (filter == HomeFilter.Offline) loadOffline()
    }

    val sections = when (filter) {
        HomeFilter.Offline -> listOfNotNull(offlineSection)
        else -> feed?.sections.orEmpty().filter { filter.matches(it.kind) }
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load(); loadOffline() } },
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
                        onOpenDownloads = onOpenDownloads,
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
                            when (filter) {
                                HomeFilter.Offline -> "Nothing downloaded yet — tap download on Home tiles or long-press for more."
                                else -> "Nothing here yet — play some music and your mixes will appear."
                            },
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
                            onDownload = { card ->
                                OfflineDownloadManager.download(context, card.playTarget)
                            },
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
