package com.bockmedia.console.ui.rooms

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.RoomItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RoomsScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var rooms by remember { mutableStateOf<List<RoomItem>>(emptyList()) }
    var remoteOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playRoom by remember { mutableStateOf<RoomItem?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            rooms = repository.rooms().rooms
            val st = repository.alexaRemoteStatus()
            remoteOk = st.configured && st.authenticated == true
        }.onFailure { error = it.message }
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

    playRoom?.let { room ->
        AlertDialog(
            onDismissRequest = { playRoom = null },
            title = { Text("Play on ${room.name}") },
            text = {
                OutlinedTextField(
                    playlistName,
                    { playlistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        room.serial?.let { serial ->
                            repository.playOnDevice(serial, "playlist", name = playlistName.trim())
                        }
                        playRoom = null
                        playlistName = ""
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { playRoom = null }) { Text("Cancel") } },
        )
    }

    when {
        loading -> LoadingBox()
        error != null -> ErrorText(error!!) { scope.launch { load() } }
        else -> BockPullRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; scope.launch { load() } },
            modifier = Modifier.fillMaxSize(),
        ) {
        BockLazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rooms) { room ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(room.name ?: "Room", style = MaterialTheme.typography.titleMedium)
                        val np = room.nowPlaying
                        Text(np?.track ?: "Idle — ${np?.artist ?: ""}")
                        if (remoteOk && !room.pseudo && room.serial != null) {
                            TextButton(onClick = { playRoom = room }) { Text("Play…") }
                        }
                        room.automations.forEach { Text("⏰ ${it.name ?: it.label ?: ""} @ ${it.time}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        }
    }
}
