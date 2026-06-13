package com.bockmedia.console.ui.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockTextField
import kotlinx.coroutines.launch

@Composable
fun DeviceFixWizard(
    repository: BockMediaRepository,
    speakers: List<AlexaDevice>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var roomName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val speaker = speakers.getOrNull(index)
    if (speaker == null) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    LaunchedEffect(speaker.serial) {
        roomName = speaker.name.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix my devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Speaker ${index + 1} of ${speakers.size}. Play a clip to hear which room this is, then name it to match the Alexa app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(speaker.name ?: speaker.serial ?: "Speaker", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedButton(
                    onClick = {
                        val serial = speaker.serial ?: return@OutlinedButton
                        scope.launch {
                            busy = true
                            error = null
                            runCatching {
                                repository.testDevice(serial, roomName.ifBlank { speaker.name })
                            }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && speaker.serial != null,
                ) {
                    Text("Play here")
                }
                BockTextField(roomName, { roomName = it }, "Room name")
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && roomName.isNotBlank(),
                onClick = {
                    val serial = speaker.serial ?: return@TextButton
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            repository.testDevice(serial, roomName.trim())
                        }.onSuccess {
                            if (index + 1 >= speakers.size) {
                                onComplete()
                            } else {
                                index++
                            }
                        }.onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text(if (index + 1 >= speakers.size) "Finish" else "Save & next") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (index + 1 >= speakers.size) onComplete() else index++
                }) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
