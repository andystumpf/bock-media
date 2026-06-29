package com.bockmedia.console.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToRoomSheet(
    repository: BockMediaRepository,
    path: String,
    track: String,
    artist: String?,
    remoteOk: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deviceOptions by remember { mutableStateOf(DeviceCatalog.peek()?.options.orEmpty()) }
    var loading by remember { mutableStateOf(deviceOptions.isEmpty()) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val snap = DeviceCatalog.refresh(repository, probe = false)
            deviceOptions = snap.options.filter { !it.value.startsWith("group:") }
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Add to room", style = MaterialTheme.typography.titleLarge)
            Text(
                track,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            when {
                !remoteOk -> Text("Connect Alexa to queue songs on speakers.")
                loading -> Text("Loading speakers…")
                deviceOptions.isEmpty() -> Text("No speakers found.")
                else -> LazyColumn {
                    items(deviceOptions, key = { it.value }) { opt ->
                        Text(
                            opt.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !submitting) {
                                    submitting = true
                                    scope.launch {
                                        runCatching {
                                            repository.roomRequest(
                                                deviceId = opt.value,
                                                path = path,
                                                track = track,
                                                artist = artist,
                                            )
                                        }.onSuccess { item ->
                                            val msg = if (item.status == "queued") {
                                                "Request sent — waiting for approval"
                                            } else {
                                                "Added to ${opt.label.trimEnd()}"
                                            }
                                            onSuccess(msg)
                                            onDismiss()
                                        }.onFailure { e ->
                                            onError(e.message ?: "Could not add to room")
                                            submitting = false
                                        }
                                    }
                                }
                                .padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
