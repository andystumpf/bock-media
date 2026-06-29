package com.bockmedia.console.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.DeviceItem
import com.bockmedia.console.data.api.dto.RoomRequestItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.launch

data class PendingRoomRequest(val device: DeviceItem, val request: RoomRequestItem)

@Composable
fun FamilyRoomRequestsSection(
    repository: BockMediaRepository,
    rooms: List<DeviceItem>,
    householdMembers: List<com.bockmedia.console.data.api.dto.HouseholdMember>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<PendingRoomRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val actingId = ActiveProfileStore.activeMemberId(context).orEmpty()
    val isParent = householdMembers.any { it.id == actingId && it.role == "parent" }

    suspend fun reload() {
        loading = true
        val all = rooms.flatMap { room ->
            val queue = runCatching { repository.roomQueue(room.deviceId).queue }.getOrDefault(emptyList())
            queue.filter { it.status == "queued" || it.status == "approved" }
                .map { PendingRoomRequest(room, it) }
        }
        pending = all
        loading = false
    }

    LaunchedEffect(rooms) { reload() }

    SectionCard("Room requests") {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (pending.isEmpty()) {
            Text("No pending room requests.", color = BockMuted, style = MaterialTheme.typography.bodySmall)
        } else {
            pending.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.request.track ?: "Track", fontWeight = FontWeight.Medium)
                        val who = item.request.byMemberName ?: "Someone"
                        val badge = if (item.request.status == "queued") "Awaiting approval" else "Approved"
                        Text(
                            "${item.device.name ?: item.device.deviceId} · $who · $badge",
                            style = MaterialTheme.typography.bodySmall,
                            color = BockMuted,
                        )
                    }
                    if (isParent && item.request.status == "queued") {
                        IconButton(onClick = {
                            val pin = ParentPinCache.get(actingId).orEmpty()
                            if (pin.isBlank()) return@IconButton
                            scope.launch {
                                runCatching {
                                    repository.approveRoomRequest(item.device.deviceId, item.request.id, pin)
                                }
                                reload()
                            }
                        }) { Icon(Icons.Default.Check, contentDescription = "Approve") }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { repository.deleteRoomRequest(item.device.deviceId, item.request.id) }
                            reload()
                        }
                    }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                }
            }
        }
    }
}
