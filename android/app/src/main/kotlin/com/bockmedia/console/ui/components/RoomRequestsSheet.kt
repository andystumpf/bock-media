package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.RoomRequestItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.family.ParentPinCache
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomRequestsSheet(
    deviceId: String,
    deviceName: String?,
    requests: List<RoomRequestItem>,
    repository: BockMediaRepository,
    actingMemberId: String?,
    isParent: Boolean,
    onUpdated: suspend () -> List<RoomRequestItem>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember(requests) { mutableStateOf(requests.filter { it.status != "done" }) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            items = onUpdated().filter { it.status != "done" }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            item {
                Text(
                    "Room queue · ${deviceName ?: deviceId}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "No household requests for this room.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(items, key = { _, r -> r.id }) { index, req ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(req.track ?: "Track", style = MaterialTheme.typography.bodyLarge)
                        val who = req.byMemberName?.takeIf { it.isNotBlank() } ?: "Someone"
                        val status = when (req.status) {
                            "queued" -> " · awaiting approval"
                            "approved" -> " · queued"
                            else -> ""
                        }
                        Text("$who$status", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isParent && req.status == "queued") {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                val pin = ParentPinCache.get(actingMemberId.orEmpty()).orEmpty()
                                if (pin.isBlank()) return@IconButton
                                scope.launch {
                                    busy = true
                                    runCatching {
                                        repository.approveRoomRequest(deviceId, req.id, pin)
                                    }
                                    refresh()
                                }
                            },
                        ) { Icon(Icons.Default.Check, contentDescription = "Approve") }
                    }
                    if (isParent) {
                        IconButton(enabled = !busy && index > 0, onClick = {
                            val ids = items.map { it.id }.toMutableList()
                            val tmp = ids[index - 1]
                            ids[index - 1] = ids[index]
                            ids[index] = tmp
                            scope.launch {
                                busy = true
                                runCatching { repository.reorderRoomRequests(deviceId, ids) }
                                refresh()
                            }
                        }) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move up") }
                        IconButton(enabled = !busy && index < items.lastIndex, onClick = {
                            val ids = items.map { it.id }.toMutableList()
                            val tmp = ids[index + 1]
                            ids[index + 1] = ids[index]
                            ids[index] = tmp
                            scope.launch {
                                busy = true
                                runCatching { repository.reorderRoomRequests(deviceId, ids) }
                                refresh()
                            }
                        }) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move down") }
                    }
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { repository.deleteRoomRequest(deviceId, req.id) }
                                refresh()
                            }
                        },
                    ) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                }
            }
        }
    }
}
