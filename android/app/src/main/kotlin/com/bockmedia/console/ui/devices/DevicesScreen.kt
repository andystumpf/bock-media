package com.bockmedia.console.ui.devices

import androidx.compose.foundation.background
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.theme.HomePillActive
import com.bockmedia.console.ui.theme.HomePillInactive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CardBg = Color(0xFF282828)
private val PillShape = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(12.dp)

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
    var renameDevice by remember { mutableStateOf<DeviceItem?>(null) }
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
        GroupEditorSheet(
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

    renameDevice?.let { dev ->
        DeviceRenameSheet(
            device = dev,
            onDismiss = { renameDevice = null },
            onSave = { name ->
                scope.launch {
                    repository.renameDevice(dev.deviceId, name)
                    renameDevice = null
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
            BockLazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    DevicesHeader(
                        online = alexaDevices.count { it.online },
                        total = alexaDevices.size,
                        groups = groups.size,
                    )
                }
                item {
                    DevicesActionPills(
                        showFix = alexaDevices.any { it.online },
                        identifying = identify?.running == true,
                        onFix = { showFixWizard = true },
                        onNewGroup = { showNewGroup = true },
                        onIdentify = {
                            scope.launch {
                                repository.identifyDevices()
                                identify = repository.identifyStatus()
                            }
                        },
                    )
                }
                if (candidates.isNotEmpty()) {
                    item { SectionTitle("Likely duplicates") }
                    items(candidates, key = { "${it.sourceId}-${it.targetId}" }) { c ->
                        DuplicateCard(
                            candidate = c,
                            onMerge = { scope.launch { repository.mergeDevices(c.sourceId, c.targetId); load() } },
                            onDismiss = { scope.launch { repository.dismissMergeCandidate(c.sourceId); load() } },
                        )
                    }
                }
                if (groups.isNotEmpty()) {
                    item { SectionTitle("Speaker groups") }
                    items(groups, key = { it.id }) { g ->
                        GroupRow(
                            group = g,
                            onEdit = { editGroup = g },
                            onDelete = { scope.launch { repository.deleteDeviceGroup(g.id); load() } },
                        )
                    }
                }
                item { SectionTitle("Speakers") }
                if (alexaDevices.isEmpty()) {
                    item {
                        Text(
                            "No Alexa speakers found. Check Alexa remote in Settings.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                } else {
                    items(alexaDevices, key = { it.serial ?: it.name.orEmpty() }) { d ->
                        SpeakerRow(
                            device = d,
                            onTest = {
                                d.serial?.let { serial ->
                                    scope.launch { repository.testDevice(serial); delay(11000); load() }
                                }
                            },
                        )
                    }
                }
                if (devices.isNotEmpty()) {
                    item { SectionTitle("Saved device names") }
                    items(devices, key = { it.deviceId }) { dev ->
                        SavedDeviceRow(
                            device = dev,
                            onClick = { renameDevice = dev },
                            onDelete = { scope.launch { repository.deleteDevice(dev.deviceId); load() } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesHeader(online: Int, total: Int, groups: Int) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            "Alexa devices",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                total == 0 -> "Connect Alexa remote in Settings"
                else -> "$online online · $total speakers · $groups groups"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DevicesActionPills(
    showFix: Boolean,
    identifying: Boolean,
    onFix: () -> Unit,
    onNewGroup: () -> Unit,
    onIdentify: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showFix) ActionPill("Fix speakers", Icons.Default.Build, onFix)
        ActionPill("New group", Icons.Default.SpeakerGroup, onNewGroup)
        ActionPill(
            label = if (identifying) "Identifying…" else "Identify all",
            icon = Icons.Default.RecordVoiceOver,
            onClick = onIdentify,
            enabled = !identifying,
        )
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = HomePillInactive,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun DuplicateCard(
    candidate: MergeCandidate,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = CardShape,
        color = CardBg,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${candidate.sourceName ?: candidate.sourceId} → ${candidate.targetName ?: candidate.targetId}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMerge) {
                    Text("Merge", color = BockGreen)
                }
                TextButton(onClick = onDismiss) {
                    Text("Not duplicate", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: DeviceGroup,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onEdit),
        shape = CardShape,
        color = CardBg,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HomePillActive.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SpeakerGroup, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${group.members.size} speakers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpeakerRow(
    device: AlexaDevice,
    onTest: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(HomePillInactive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Speaker, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name ?: device.serial ?: "Speaker",
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (device.online) "Online" else "Offline",
                style = MaterialTheme.typography.bodySmall,
                color = if (device.online) BockGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (device.online) BockGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onTest, enabled = device.online) {
            Icon(Icons.Default.VolumeUp, contentDescription = "Test speaker", tint = BockGreen)
        }
    }
}

@Composable
private fun SavedDeviceRow(
    device: DeviceItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name ?: device.deviceId,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Tap to rename",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditorSheet(
    group: DeviceGroup?,
    alexaDevices: List<AlexaDevice>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(group?.name ?: "") }
    var selected by remember { mutableStateOf(group?.members?.map { it.serial }?.toSet() ?: emptySet()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (group == null) "New speaker group" else "Edit group",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            BockTextField(name, { name = it }, "Group name")
            Text("Speakers", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f))
            alexaDevices.forEach { d ->
                val serial = d.serial ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selected = if (serial in selected) selected - serial else selected + serial
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = serial in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + serial else selected - serial
                        },
                    )
                    Text(d.name ?: serial, color = Color.White)
                }
            }
            Button(
                onClick = { onSave(name, selected.toList()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BockGreen, contentColor = Color.Black),
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRenameSheet(
    device: DeviceItem,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(device.name ?: "") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rename device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            BockTextField(name, { name = it }, "Device name")
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BockGreen, contentColor = Color.Black),
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}
