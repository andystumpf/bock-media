package com.bockmedia.console.ui.devices

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.devices.DeviceFixWizard
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Composable
fun DevicesScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<MergeCandidate>>(emptyList()) }
    var groups by remember { mutableStateOf<List<DeviceGroup>>(emptyList()) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var identify by remember { mutableStateOf<IdentifyStatusResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editGroup by remember { mutableStateOf<DeviceGroup?>(null) }
    var showNewGroup by remember { mutableStateOf(false) }

    var showFixWizard by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            devices = repository.devices()
            candidates = repository.mergeCandidates().candidates
            groups = repository.deviceGroups().items
            runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
            identify = repository.identifyStatus()
        }.onFailure { error = it.message }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(identify?.running) {
        if (identify?.running == true) {
            while (true) {
                delay(1500)
                identify = repository.identifyStatus()
                if (identify?.running != true) break
            }
            load()
        }
    }

    if (showNewGroup || editGroup != null) {
        GroupEditorDialog(
            group = editGroup,
            alexaDevices = alexaDevices,
            onDismiss = { showNewGroup = false; editGroup = null },
            onSave = { name, serials ->
                scope.launch {
                    if (editGroup != null) {
                        repository.updateDeviceGroup(editGroup!!.id, name, serials)
                    } else {
                        repository.createDeviceGroup(name, serials)
                    }
                    showNewGroup = false
                    editGroup = null
                    load()
                }
            },
        )
    }

    if (showFixWizard) {
        DeviceFixWizard(
            repository = repository,
            speakers = alexaDevices.filter { it.online && !it.serial.isNullOrBlank() },
            onDismiss = { showFixWizard = false },
            onComplete = { showFixWizard = false; scope.launch { load() } },
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
            item {
                val unnamed = devices.count { (it.name ?: "").matches(Regex("(?i).*echo.*|.*speaker.*|.*dot.*")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alexaDevices.any { it.online }) {
                        Button(onClick = { showFixWizard = true }) {
                            Text("Fix my devices")
                        }
                    }
                    if (unnamed > 0) {
                        Text(
                            "$unnamed may need naming",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                        )
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                item { Text("Likely duplicates", style = MaterialTheme.typography.titleSmall) }
                items(candidates) { c ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch { repository.mergeDevices(c.sourceId, c.targetId); load() }
                        }) { Text("Merge") }
                        TextButton(onClick = {
                            scope.launch { repository.dismissMergeCandidate(c.sourceId); load() }
                        }) { Text("Not duplicate") }
                        Text("${c.sourceName ?: c.sourceId} → ${c.targetName ?: c.targetId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row {
                    Text("Device groups", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showNewGroup = true }) { Text("New group") }
                    TextButton(onClick = { scope.launch { repository.identifyDevices(); identify = repository.identifyStatus() } }) {
                        Text(if (identify?.running == true) "Identifying…" else "Identify all")
                    }
                }
            }
            items(groups) { g ->
                ListItem(
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text("${g.members.size} speakers") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { editGroup = g }) { Text("Edit") }
                            TextButton(onClick = { scope.launch { repository.deleteDeviceGroup(g.id); load() } }) { Text("Delete") }
                        }
                    },
                )
            }
            item { Text("Speakers", style = MaterialTheme.typography.titleSmall) }
            items(alexaDevices) { d ->
                ListItem(
                    headlineContent = { Text(d.name ?: d.serial ?: "?") },
                    trailingContent = {
                        TextButton(onClick = {
                            d.serial?.let { scope.launch { repository.testDevice(it); delay(11000); load() } }
                        }) { Text("Test") }
                    },
                )
            }
            item { Text("Known devices", style = MaterialTheme.typography.titleSmall) }
            items(devices) { dev ->
                var editName by remember(dev.deviceId) { mutableStateOf(dev.name ?: "") }
                Column {
                    OutlinedTextField(editName, { editName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = {
                            scope.launch { repository.renameDevice(dev.deviceId, editName); load() }
                        }) { Text("Save") }
                        TextButton(onClick = {
                            scope.launch { repository.deleteDevice(dev.deviceId); load() }
                        }) { Text("Delete") }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun GroupEditorDialog(
    group: DeviceGroup?,
    alexaDevices: List<AlexaDevice>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(group?.name ?: "") }
    var selected by remember { mutableStateOf(group?.members?.map { it.serial }?.toSet() ?: emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group == null) "New group" else "Edit group") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                alexaDevices.forEach { d ->
                    val serial = d.serial ?: return@forEach
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = serial in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + serial else selected - serial
                        })
                        Text(d.name ?: serial)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, selected.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
