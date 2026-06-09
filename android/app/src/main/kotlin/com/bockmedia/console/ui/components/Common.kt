package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import com.bockmedia.console.domain.model.PlayTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.DeviceGroup
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.util.formatTime12
import com.bockmedia.console.ui.util.formatTime24
import com.bockmedia.console.ui.util.parseTime24
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun bockTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        readOnly = readOnly,
        singleLine = singleLine,
        shape = RoundedCornerShape(16.dp),
        colors = bockTextFieldColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BockTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
    )
}

data class DeviceOption(val value: String, val label: String)

fun buildDeviceOptions(groups: List<DeviceGroup>, devices: List<AlexaDevice>): List<DeviceOption> {
    val out = mutableListOf<DeviceOption>()
    groups.forEach { g ->
        out += DeviceOption("group:${g.id}", "${g.name} (${g.members?.size ?: 0})")
    }
    devices.forEach { d ->
        val serial = d.serial ?: return@forEach
        val name = d.name ?: serial
        val label = if (d.online) name else "$name (offline)"
        out += DeviceOption(serial, label)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectField(
    options: List<DeviceOption>,
    selectedValue: String,
    onSelect: (DeviceOption) -> Unit,
    placeholder: String = "Select device…",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.value == selectedValue }?.label ?: ""
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }, modifier = modifier) {
        BockTextField(
            selectedLabel,
            {},
            placeholder,
            readOnly = true,
            modifier = Modifier.menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No devices found") },
                    onClick = {},
                    enabled = false,
                )
            } else {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BockTimeField(
    time24: String,
    onTimeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val (hour, minute) = parseTime24(time24)
    val display = formatTime12(time24)

    Box(modifier = modifier) {
        BockTextField(
            value = display,
            onValueChange = {},
            placeholder = "Time",
            readOnly = true,
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { showPicker = true },
        )
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(formatTime24(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun SearchActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
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
    onPlay: suspend (device: String, shuffle: Boolean) -> Unit,
    onPlayError: suspend (Throwable) -> Unit = {},
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
            groups = repository.deviceGroups().items
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
                    BockLazyColumn(Modifier.heightIn(max = 400.dp)) {
                        if (groups.isNotEmpty()) {
                            item { Text("Groups", style = MaterialTheme.typography.labelLarge) }
                            items(groups) { g ->
                                ListItem(
                                    headlineContent = { Text(g.name) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                runCatching {
                                                    onPlay("group:${g.id}", shuffle)
                                                }.onFailure { onPlayError(it) }
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
                                        val serial = d.serial ?: d.name ?: return@clickable
                                        scope.launch {
                                            runCatching {
                                                onPlay(serial, shuffle)
                                            }.onFailure { onPlayError(it) }
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
    padding(vertical = 2.dp).clickable(onClick = onClick)

@Composable
fun PlayButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.secondary)
    }
}
