package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.bockmedia.console.domain.model.PlayTarget
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.DeviceGroup
import com.bockmedia.console.data.repository.BockMediaRepository
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.launch

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorText(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
    )
}

@Composable
fun PaginationBar(page: Int, totalPages: Int, onPage: (Int) -> Unit) {
    if (totalPages <= 1) return
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 1) { Text("Prev") }
        Text("$page / $totalPages")
        TextButton(onClick = { onPage(page + 1) }, enabled = page < totalPages) { Text("Next") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    repository: BockMediaRepository,
    onDismiss: () -> Unit,
    onPlay: (device: String, shuffle: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var groups by remember { mutableStateOf<List<DeviceGroup>>(emptyList()) }
    var shuffle by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            devices = repository.alexaRemoteDevices().devices
            groups = repository.deviceGroups().groups
        }.onFailure { error = it.message }.also { loading = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text("Play on device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shuffle, onCheckedChange = { shuffle = it })
                Text("Shuffle (mix)")
            }
            when {
                loading -> CircularProgressIndicator()
                error != null -> ErrorText(error!!)
                else -> {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        if (groups.isNotEmpty()) {
                            item { Text("Groups", style = MaterialTheme.typography.labelLarge) }
                            items(groups) { g ->
                                ListItem(
                                    headlineContent = { Text(g.name) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                onPlay("group:${g.id}", shuffle)
                                                onDismiss()
                                            }
                                        },
                                )
                            }
                        }
                        item { Text("Speakers", style = MaterialTheme.typography.labelLarge) }
                        items(devices) { d ->
                            ListItem(
                                headlineContent = { Text(d.name ?: d.serial ?: "?") },
                                supportingContent = {
                                    Text(if (d.online) "Online" else "Offline")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val target = d.serial ?: d.name ?: return@clickable
                                        scope.launch {
                                            onPlay(target, shuffle)
                                            onDismiss()
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.padding(vertical = 2.dp)).let { m ->
        androidx.compose.foundation.clickable(onClick = onClick).then(m)
    }

@Composable
fun PlayButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.secondary)
    }
}
