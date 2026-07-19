package com.bockmedia.console.ui.rooms

import androidx.compose.foundation.background
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.RoomItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.foundation.lazy.items
import com.bockmedia.console.ui.theme.HomePillInactive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CardBg = Color(0xFF282828)
private val PillShape = RoundedCornerShape(50)

@Composable
fun RoomsScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<RoomItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { rooms = repository.rooms().rooms }.onFailure { error = it.message }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(8_000)
            runCatching { rooms = repository.rooms().rooms }
        }
    }

    val playing = remember(rooms) { rooms.filter { it.nowPlaying?.track != null && it.nowPlaying?.paused != true } }
    val paused = remember(rooms) { rooms.filter { it.nowPlaying?.paused == true } }
    val idle = remember(rooms) { rooms.filter { it.nowPlaying?.track.isNullOrBlank() } }

    when {
        loading -> LoadingBox()
        error != null -> ErrorText(error!!) { scope.launch { load() } }
        else -> BockPullRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; scope.launch { load() } },
            modifier = Modifier.fillMaxSize().testTag(BockTestTags.ROOMS_BODY),
        ) {
            BockLazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    RoomsHeader(
                        total = rooms.size,
                        playingCount = playing.size + paused.size,
                    )
                }
                if (rooms.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No Echo devices found.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (playing.isNotEmpty()) {
                    item { RoomSectionTitle("Now playing") }
                    items(playing, key = { it.serial ?: it.deviceId ?: it.name.orEmpty() }) { room ->
                        RoomRow(room, repository, remoteOk, onPlay, onOpenNowPlaying, playing = true)
                    }
                }
                if (paused.isNotEmpty()) {
                    item { RoomSectionTitle("Paused") }
                    items(paused, key = { "p-${it.serial ?: it.deviceId}" }) { room ->
                        RoomRow(room, repository, remoteOk, onPlay, onOpenNowPlaying, playing = false, paused = true)
                    }
                }
                if (idle.isNotEmpty()) {
                    item { RoomSectionTitle("Idle") }
                    items(idle, key = { "i-${it.serial ?: it.deviceId ?: it.name}" }) { room ->
                        RoomRow(room, repository, remoteOk, onPlay, onOpenNowPlaying, playing = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomsHeader(total: Int, playingCount: Int) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            "Rooms",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                total == 0 -> "Connect Alexa remote in Settings"
                playingCount > 0 -> "$playingCount of $total playing"
                else -> "$total speakers · all idle"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoomSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun RoomRow(
    room: RoomItem,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenNowPlaying: () -> Unit,
    playing: Boolean,
    paused: Boolean = false,
) {
    val np = room.nowPlaying
    val title = np?.track ?: room.name ?: "Room"
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artistName = np?.artist,
        albumName = np?.album,
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpenNowPlaying)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) {
            if (np?.track != null) {
                BockArtwork(
                    model = artUrl,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(6.dp),
                    fallbackFontSize = 18.sp,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(HomePillInactive),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Speaker,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (playing || paused) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (playing) BockGreen else Color(0xFFE8A838)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.name ?: "Room",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                np?.track ?: "Nothing playing",
                style = MaterialTheme.typography.bodyMedium,
                color = if (np?.track != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                np?.artist,
                np?.sourceLabel?.takeIf { np.track != null },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (room.automations.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    room.automations.take(2).forEach { auto ->
                        Surface(
                            shape = PillShape,
                            color = CardBg,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    listOfNotNull(auto.name ?: auto.label, auto.time).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (remoteOk && !room.pseudo && room.serial != null && np?.playlist != null) {
            PlayButton(onClick = { onPlay(PlayTarget.Playlist("", np.playlist.orEmpty())) })
        }
    }
}
