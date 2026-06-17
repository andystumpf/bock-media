package com.bockmedia.console.ui.automation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AutomationItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.AutomationSessionCache
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.util.formatTime12
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private enum class DayPreset { Daily, Weekdays, Weekends, Custom }

private fun presetDays(preset: DayPreset): Set<Int> = when (preset) {
    DayPreset.Daily -> (0..6).toSet()
    DayPreset.Weekdays -> setOf(0, 1, 2, 3, 4)
    DayPreset.Weekends -> setOf(5, 6)
    DayPreset.Custom -> emptySet()
}

private fun formatAutomationDays(days: List<Int>): String {
    if (days.isEmpty()) return "No days"
    val set = days.toSet()
    if (set.size == 7) return "Daily"
    if (set == setOf(0, 1, 2, 3, 4)) return "Mon–Fri"
    if (set == setOf(5, 6)) return "Weekends"
    return days.sorted().joinToString(", ") { DAY_LABELS.getOrElse(it) { "?" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    repository: BockMediaRepository,
    onAccountNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val cachedAutomation = AutomationSessionCache.peek()
    var automations by remember { mutableStateOf(cachedAutomation?.items.orEmpty()) }
    var remoteOk by remember { mutableStateOf(cachedAutomation?.remoteOk ?: false) }
    var loading by remember { mutableStateOf(cachedAutomation == null) }
    var refreshing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<AutomationItem?>(null) }

    suspend fun load(fromPull: Boolean = false) {
        if (!fromPull && automations.isEmpty()) loading = true
        if (fromPull) refreshing = true
        runCatching {
            val loaded = repository.automations().items
            val st = repository.alexaRemoteStatus()
            val ok = st.configured && st.authenticated == true
            automations = loaded
            remoteOk = ok
            AutomationSessionCache.put(loaded, ok)
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        if (AutomationSessionCache.getIfFresh() != null) {
            scope.launch { load() }
        } else {
            load()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (remoteOk) {
                FloatingActionButton(onClick = { editItem = null; showSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add automation")
                }
            }
        },
    ) { padding ->
        BockPullRefresh(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(fromPull = true) } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            TabScreenHeader("Automations") {
                AccountMenuButton(onAccountNavigate)
            }
            if (!remoteOk) {
                Text(
                    "Configure Alexa remote in Settings first.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (loading) {
                LoadingBox(Modifier.weight(1f))
            } else {
                BockLazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(automations, key = { it.id }) { auto ->
                        ListItem(
                            headlineContent = { Text(auto.name.ifBlank { auto.label }) },
                            supportingContent = {
                                Text(
                                    "${formatTime12(auto.time ?: "08:00")} · ${formatAutomationDays(auto.days)} · ${auto.playlistName ?: auto.playlist ?: ""} · ${auto.deviceName ?: auto.device ?: ""}",
                                )
                            },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { scope.launch { repository.runAutomation(auto.id) } }) {
                                        Text("Run")
                                    }
                                    TextButton(onClick = {
                                        editItem = auto
                                        showSheet = true
                                    }) { Text("Edit") }
                                    TextButton(onClick = {
                                        scope.launch { repository.deleteAutomation(auto.id); load() }
                                    }) { Text("Delete") }
                                }
                            },
                        )
                    }
                }
            }
        }
        }
    }

    if (showSheet && remoteOk) {
        AutomationFormSheet(
            repository = repository,
            editItem = editItem,
            onDismiss = { showSheet = false; editItem = null },
            onSaved = {
                showSheet = false
                editItem = null
                scope.launch { load() }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationFormSheet(
    repository: BockMediaRepository,
    editItem: AutomationItem?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by remember(editItem) { mutableStateOf(editItem?.name?.ifBlank { editItem.label } ?: editItem?.label ?: "") }
    var playlistSearch by remember(editItem) { mutableStateOf(editItem?.playlistName ?: editItem?.playlist ?: "") }
    var playlistPick by remember(editItem) {
        mutableStateOf((editItem?.playlistId to (editItem?.playlistName ?: editItem?.playlist)))
    }
    var deviceValue by remember(editItem) { mutableStateOf(editItem?.device ?: "") }
    var deviceName by remember(editItem) { mutableStateOf(editItem?.deviceName ?: "") }
    var deviceOptions by remember { mutableStateOf<List<DeviceOption>>(emptyList()) }
    var time by remember(editItem) { mutableStateOf(editItem?.time ?: "08:00") }
    var volume by remember(editItem) { mutableStateOf(editItem?.volume?.toString() ?: "") }
    var shuffle by remember(editItem) { mutableStateOf(editItem?.shuffle ?: false) }
    var enabled by remember(editItem) { mutableStateOf(editItem?.enabled ?: true) }
    var playlistHits by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dayPreset by remember(editItem) {
        mutableStateOf(
            when {
                editItem == null -> DayPreset.Daily
                editItem.days.toSet() == presetDays(DayPreset.Daily) -> DayPreset.Daily
                editItem.days.toSet() == presetDays(DayPreset.Weekdays) -> DayPreset.Weekdays
                editItem.days.toSet() == presetDays(DayPreset.Weekends) -> DayPreset.Weekends
                else -> DayPreset.Custom
            },
        )
    }
    var customDays by remember(editItem) {
        mutableStateOf(editItem?.days?.toSet() ?: presetDays(DayPreset.Daily))
    }

    LaunchedEffect(Unit) {
        runCatching {
            val devs = repository.alexaRemoteDevices().devices
            val groups = repository.deviceGroups().items
            deviceOptions = buildDeviceOptions(groups, devs)
        }
    }

    LaunchedEffect(playlistSearch, playlistPick) {
        delay(250)
        if (playlistPick.first != null || playlistSearch.length < 1) {
            playlistHits = emptyList()
            return@LaunchedEffect
        }
        runCatching {
            playlistHits = repository.playlists(playlistSearch, limit = 25).items.map { it.id to it.name }
        }
    }

    val selectedDays = when (dayPreset) {
        DayPreset.Custom -> customDays
        else -> presetDays(dayPreset)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (editItem != null) "Edit automation" else "New automation",
                style = MaterialTheme.typography.titleLarge,
            )
            BockTextField(label, { label = it }, "Label")
            SearchField(
                playlistSearch,
                { query ->
                    playlistSearch = query
                    if (playlistPick.second != null && query != playlistPick.second) {
                        playlistPick = null to null
                    }
                },
                "Search playlist",
            )
            if (playlistPick.first == null && playlistHits.isNotEmpty()) {
                playlistHits.take(8).forEach { (id, name) ->
                    TextButton(onClick = {
                        playlistPick = id to name
                        playlistSearch = name
                        playlistHits = emptyList()
                    }) { Text(name) }
                }
            }
            DeviceSelectField(
                options = deviceOptions,
                selectedValue = deviceValue,
                onSelect = { opt ->
                    deviceValue = opt.value
                    deviceName = opt.label.replace(" (offline)", "")
                },
                placeholder = "Select device…",
            )
            BockTimeField(time, { time = it })
            BockTextField(volume, { volume = it }, "Volume (optional)")
            Text("Days", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = dayPreset == preset,
                        onClick = {
                            dayPreset = preset
                            if (preset != DayPreset.Custom) customDays = presetDays(preset)
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
            if (dayPreset == DayPreset.Custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    DAY_LABELS.forEachIndexed { index, day ->
                        FilterChip(
                            selected = index in customDays,
                            onClick = {
                                customDays = if (index in customDays) customDays - index else customDays + index
                            },
                            label = { Text(day) },
                        )
                    }
                }
            }
            Row {
                Checkbox(shuffle, { shuffle = it })
                Text("Shuffle")
                Spacer(Modifier.width(16.dp))
                Checkbox(enabled, { enabled = it })
                Text("Enabled")
            }
            Button(
                onClick = {
                    scope.launch {
                        val body = buildJsonObject {
                            put("name", label)
                            playlistPick.first?.let { put("playlistId", it) }
                            playlistPick.second?.let { put("playlistName", it) }
                            put("device", deviceValue)
                            put("deviceName", deviceName)
                            put("time", time)
                            putJsonArray("days") {
                                selectedDays.sorted().forEach { add(JsonPrimitive(it)) }
                            }
                            volume.toIntOrNull()?.let { put("volume", it) }
                            put("shuffle", shuffle)
                            put("enabled", enabled)
                        }
                        if (editItem != null) repository.updateAutomation(editItem.id, body)
                        else repository.createAutomation(body)
                        onSaved()
                    }
                },
                enabled = label.isNotBlank() && deviceValue.isNotBlank() && selectedDays.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (editItem != null) "Save" else "Add automation")
            }
        }
    }
}
