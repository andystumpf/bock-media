package com.bockmedia.console.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.ui.theme.BockGreen
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val CardShape = RoundedCornerShape(12.dp)

@Composable
fun FamilyScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var household by remember { mutableStateOf(HouseholdResponse()) }
    var rooms by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var stats by remember { mutableStateOf<HouseholdAnalytics?>(null) }
    var messages by remember { mutableStateOf<List<FamilyMessage>>(emptyList()) }
    var activeMemberId by remember { mutableStateOf(ActiveProfileStore.activeMemberId(context) ?: "") }

    var showAddMember by remember { mutableStateOf(false) }
    var pinMember by remember { mutableStateOf<HouseholdMember?>(null) }
    var policyRoom by remember { mutableStateOf<DeviceItem?>(null) }

    val ownerByDevice = remember(household) {
        household.deviceOwners.mapNotNull { o -> o.memberId?.let { o.deviceId to it } }.toMap()
    }

    suspend fun loadMessages() {
        val me = ActiveProfileStore.activeMemberId(context)
        messages = runCatching { repository.messages(me).items.reversed() }.getOrDefault(emptyList())
    }

    suspend fun load() {
        household = runCatching { repository.household() }.getOrDefault(HouseholdResponse())
        rooms = runCatching { repository.devices() }.getOrDefault(emptyList())
            .filter { !it.deviceId.startsWith("client-") }
        stats = runCatching { repository.householdAnalytics() }.getOrNull()
        loadMessages()
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                SectionCard("Acting as") {
                    MemberDropdown(
                        members = household.members,
                        selectedId = activeMemberId,
                        includeNone = true,
                        noneLabel = "Unattributed",
                    ) { id ->
                        val previous = ActiveProfileStore.activeMemberId(context)
                        activeMemberId = id
                        ActiveProfileStore.setActiveMember(context, id.ifBlank { null })
                        scope.launch {
                            ClientPrefsSync.onActiveMemberChanged(context, id.ifBlank { null }, previous)
                            loadMessages()
                        }
                    }
                    Text(
                        "Attributes your plays and is used for messages, sharing, and approvals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BockMuted,
                    )
                }
            }

            item {
                SectionCard("Members") {
                    if (household.members.isEmpty()) {
                        Text("No members yet.", color = BockMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    household.members.forEach { m ->
                        MemberRow(
                            member = m,
                            onSetPin = { pinMember = m },
                            onRole = { role ->
                                scope.launch {
                                    runCatching { repository.updateMember(m.id, role) }
                                    load()
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    runCatching { repository.deleteMember(m.id) }
                                    if (activeMemberId == m.id) {
                                        activeMemberId = ""
                                        ActiveProfileStore.setActiveMember(context, null)
                                    }
                                    load()
                                }
                            },
                        )
                    }
                    TextButton(onClick = { showAddMember = true }) { Text("+ Add member") }
                }
            }

            item {
                SectionCard("Rooms — owner & kid-safe") {
                    Text(
                        "Assign a room to a person (e.g. Office → Andy). Leave community rooms unattributed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BockMuted,
                    )
                    if (rooms.isEmpty()) {
                        Text("No Echo rooms found.", color = BockMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    rooms.forEach { room ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(room.name ?: room.deviceId, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MemberDropdown(
                                    members = household.members,
                                    selectedId = ownerByDevice[room.deviceId] ?: "",
                                    includeNone = true,
                                    noneLabel = "Unattributed",
                                    modifier = Modifier.weight(1f),
                                ) { id ->
                                    scope.launch {
                                        runCatching {
                                            repository.setDeviceOwner(room.deviceId, id.ifBlank { null })
                                        }
                                        household = runCatching { repository.household() }.getOrDefault(household)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { policyRoom = room }) {
                                    Icon(Icons.Default.Shield, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Kid-safe")
                                }
                            }
                        }
                    }
                }
            }

            item {
                FamilyRoomRequestsSection(
                    repository = repository,
                    rooms = rooms,
                    householdMembers = household.members,
                )
            }

            stats?.takeIf { it.byMember.isNotEmpty() }?.let { s ->
                item {
                    SectionCard("Family activity · ${s.totalPlays} plays") {
                        val maxPlays = (s.byMember.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
                        s.byMember.take(8).forEach { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row {
                                    Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text("${row.plays}", color = BockMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(
                                    progress = { row.plays.toFloat() / maxPlays },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = BockGreen,
                                )
                            }
                        }
                        if (s.byPlatform.isNotEmpty()) {
                            Text(
                                s.byPlatform.joinToString(" · ") { "${it.platform} ${it.plays}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = BockMuted,
                            )
                        }
                    }
                }
            }

            item {
                MessagesCard(
                    members = household.members,
                    messages = messages,
                    onSend = { to, text ->
                        scope.launch {
                            runCatching {
                                repository.sendMessage(
                                    fromMemberId = ActiveProfileStore.activeMemberId(context),
                                    toMemberId = to.ifBlank { null },
                                    text = text,
                                )
                            }.onSuccess { loadMessages() }
                                .onFailure { snackbar.showSnackbar("Couldn't send") }
                        }
                    },
                )
            }
        }
    }

    if (showAddMember) {
        AddMemberDialog(
            onDismiss = { showAddMember = false },
            onAdd = { name, role ->
                showAddMember = false
                scope.launch {
                    runCatching { repository.createMember(name, role) }
                        .onSuccess { load() }
                        .onFailure { snackbar.showSnackbar("Couldn't add") }
                }
            },
        )
    }

    pinMember?.let { m ->
        SetPinDialog(
            member = m,
            onDismiss = { pinMember = null },
            onSave = { current, newPin ->
                scope.launch {
                    val resp = runCatching {
                        repository.setMemberPin(m.id, newPin, if (m.hasPin) current else null)
                    }.getOrNull()
                    if (resp?.ok == true) {
                        ParentPinCache.set(m.id, newPin)
                        pinMember = null
                        load()
                    } else {
                        snackbar.showSnackbar(resp?.error ?: "Couldn't set PIN")
                    }
                }
            },
        )
    }

    policyRoom?.let { room ->
        KidSafeDialog(
            repository = repository,
            room = room,
            actingMemberId = activeMemberId,
            onDismiss = { policyRoom = null },
            onSaved = { policyRoom = null },
            onError = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = Color_Card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private val Color_Card = androidx.compose.ui.graphics.Color(0xFF282828)

@Composable
private fun MemberRow(
    member: HouseholdMember,
    onSetPin: () -> Unit,
    onRole: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(member.name, modifier = Modifier.weight(1f))
        if (member.isParent) {
            TextButton(onClick = onSetPin) { Text(if (member.hasPin) "PIN" else "Set PIN") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Make parent") }, onClick = { menu = false; onRole("parent") })
                DropdownMenuItem(text = { Text("Make kid") }, onClick = { menu = false; onRole("kid") })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun MemberDropdown(
    members: List<HouseholdMember>,
    selectedId: String,
    includeNone: Boolean,
    noneLabel: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = members.firstOrNull { it.id == selectedId }?.name ?: noneLabel
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text(label, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (includeNone) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { open = false; onSelect("") })
            }
            members.forEach { m ->
                DropdownMenuItem(text = { Text(m.name) }, onClick = { open = false; onSelect(m.id) })
            }
        }
    }
}

@Composable
private fun MessagesCard(
    members: List<HouseholdMember>,
    messages: List<FamilyMessage>,
    onSend: (String, String) -> Unit,
) {
    var to by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    SectionCard("Music messages") {
        MemberDropdown(members = members, selectedId = to, includeNone = true, noneLabel = "Everyone") { to = it }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Say something…") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = {
                    if (text.isNotBlank()) { onSend(to, text.trim()); text = "" }
                }) { Text("Send") }
            },
        )
        messages.take(30).forEach { m ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row {
                    Text(m.fromName ?: "Someone", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    val suffix = when {
                        !m.toName.isNullOrBlank() -> " → ${m.toName}"
                        m.scope == "household" -> " → all"
                        else -> ""
                    }
                    if (suffix.isNotEmpty()) Text(suffix, color = BockMuted, style = MaterialTheme.typography.bodySmall)
                }
                m.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("kid") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = role == "kid", onClick = { role = "kid" }, label = { Text("Kid") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = role == "parent", onClick = { role = "parent" }, label = { Text("Parent") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onAdd(name.trim(), role) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetPinDialog(
    member: HouseholdMember,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parent PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (member.hasPin) {
                    OutlinedTextField(value = current, onValueChange = { current = it }, label = { Text("Current PIN") })
                }
                OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("New PIN (4+ digits)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (newPin.length >= 4) onSave(current, newPin) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KidSafeDialog(
    repository: BockMediaRepository,
    room: DeviceItem,
    actingMemberId: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var safe by remember { mutableStateOf(false) }
    var allowExplicit by remember { mutableStateOf(true) }
    var requireApproval by remember { mutableStateOf(false) }
    var enforceVolume by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100f) }
    var quietOn by remember { mutableStateOf(false) }
    var quietFrom by remember { mutableStateOf("20:30") }
    var quietTo by remember { mutableStateOf("07:00") }
    var allow by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var pin by remember { mutableStateOf(ParentPinCache.get(actingMemberId) ?: "") }

    LaunchedEffect(room.deviceId) {
        val p = runCatching { repository.roomPolicy(room.deviceId) }.getOrNull()
        if (p != null) {
            safe = p.safe
            allowExplicit = p.allowExplicit
            requireApproval = p.requireApproval
            enforceVolume = p.maxVolume != null
            volume = (p.maxVolume ?: 100).toFloat()
            allow = p.allowPlaylistIds.toSet()
            p.quietHours.firstOrNull()?.let {
                quietOn = true
                quietFrom = it.from ?: "20:30"
                quietTo = it.to ?: "07:00"
            }
        }
        playlists = runCatching { repository.playlists(limit = 500).items }.getOrDefault(emptyList())
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kid-safe — ${room.name ?: room.deviceId}") },
        text = {
            if (!loaded) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BockGreen) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                    item { SwitchRow("Enable kid-safe", safe) { safe = it } }
                    item { SwitchRow("Allow explicit content", allowExplicit) { allowExplicit = it } }
                    item { SwitchRow("Require approval for requests", requireApproval) { requireApproval = it } }
                    item { SwitchRow("Enforce volume cap", enforceVolume) { enforceVolume = it } }
                    if (enforceVolume) {
                        item {
                            Column {
                                Text("Max volume: ${volume.toInt()}", color = BockMuted, style = MaterialTheme.typography.bodySmall)
                                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f)
                            }
                        }
                    }
                    item { SwitchRow("Quiet hours", quietOn) { quietOn = it } }
                    if (quietOn) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = quietFrom, onValueChange = { quietFrom = it }, label = { Text("From") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = quietTo, onValueChange = { quietTo = it }, label = { Text("To") }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    item { Text("Allowed playlists", fontWeight = FontWeight.SemiBold) }
                    items(playlists) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = allow.contains(p.id),
                                onCheckedChange = {
                                    allow = if (it) allow + p.id else allow - p.id
                                },
                            )
                            Text(p.name)
                        }
                    }
                    item {
                        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Parent PIN") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val body = buildJsonObject {
                    put("memberId", actingMemberId)
                    put("pin", pin)
                    put("safe", safe)
                    put("allowExplicit", allowExplicit)
                    put("requireApproval", requireApproval)
                    if (enforceVolume) put("maxVolume", volume.toInt()) else put("maxVolume", null as String?)
                    putJsonArray("allowPlaylistIds") { allow.forEach { add(it) } }
                    putJsonArray("quietHours") {
                        if (quietOn) add(buildJsonObject {
                            putJsonArray("days") { (0..6).forEach { add(it) } }
                            put("from", quietFrom)
                            put("to", quietTo)
                        })
                    }
                }
                scope.launch {
                    val ok = runCatching { repository.setRoomPolicy(room.deviceId, body) }.isSuccess
                    if (ok) { ParentPinCache.set(actingMemberId, pin); onSaved() }
                    else onError("Couldn't save (check parent PIN)")
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Session-scoped parent PIN cache so kid-safe edits don't re-prompt. */
object ParentPinCache {
    private val pins = mutableMapOf<String, String>()
    fun set(memberId: String, pin: String) { pins[memberId] = pin }
    fun get(memberId: String): String? = pins[memberId]
}
