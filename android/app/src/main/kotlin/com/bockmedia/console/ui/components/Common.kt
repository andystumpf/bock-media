package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.bockmedia.console.domain.model.PlayTarget
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.DeviceGroup
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.util.formatTime12
import com.bockmedia.console.ui.util.formatTime24
import com.bockmedia.console.ui.util.parseTime24
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import kotlinx.coroutines.launch

private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifySheetBg = Color(0xFF282828)
private val SpotifyRowSelected = Color(0xFF3E3E3E)
private val SpotifyMuted = Color(0xFFB3B3B3)

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
    playLabel: String? = null,
    remoteOk: Boolean = true,
    shuffleDefault: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: suspend (device: String, shuffle: Boolean, deviceLabel: String) -> Unit,
    onPlayError: suspend (Throwable) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var deviceOptions by remember { mutableStateOf<List<DeviceOption>>(emptyList()) }
    var deviceValue by remember { mutableStateOf("") }
    var shuffle by remember(shuffleDefault) { mutableStateOf(shuffleDefault) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            val devices = repository.alexaRemoteDevices().devices
            val groups = repository.deviceGroups().items
            deviceOptions = buildDeviceOptions(groups, devices)
            deviceValue = deviceOptions.firstOrNull { !it.label.contains("offline", true) }?.value
                ?: deviceOptions.firstOrNull()?.value.orEmpty()
        }.onFailure { error = it.message }.also { loading = false }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifySheetBg,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                "Play on",
                style = MaterialTheme.typography.labelLarge,
                color = SpotifyMuted,
            )
            Text(
                playLabel ?: "Choose a speaker",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            if (!remoteOk) {
                Text(
                    "Connect Alexa remote in Settings to play.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            when {
                loading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    }
                }
                error != null -> ErrorText(error!!)
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(deviceOptions, key = { it.value }) { opt ->
                            val selected = opt.value == deviceValue
                            val offline = opt.label.contains("offline", ignoreCase = true)
                            Surface(
                                onClick = { if (!offline) deviceValue = opt.value },
                                enabled = !offline,
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) SpotifyRowSelected else Color.White.copy(alpha = 0.06f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (opt.value.startsWith("group:")) Icons.Default.SpeakerGroup
                                        else Icons.Default.Speaker,
                                        contentDescription = null,
                                        tint = if (offline) SpotifyMuted.copy(alpha = 0.5f) else Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        opt.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (offline) SpotifyMuted else Color.White,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = SpotifyGreen,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Surface(
                        onClick = { if (remoteOk) shuffle = !shuffle },
                        enabled = remoteOk,
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = if (shuffle) SpotifyGreen else Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Shuffle",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = shuffle,
                                onCheckedChange = { shuffle = it },
                                enabled = remoteOk,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SpotifyGreen,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (deviceValue.isBlank()) return@Button
                            scope.launch {
                                playing = true
                                runCatching {
                                    val label = deviceOptions.find { it.value == deviceValue }?.label ?: deviceValue
                                    onPlay(deviceValue, shuffle, label)
                                }.onFailure { onPlayError(it) }
                                playing = false
                                onDismiss()
                            }
                        },
                        enabled = !playing && deviceValue.isNotBlank() && remoteOk,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen,
                            contentColor = Color.Black,
                            disabledContainerColor = SpotifyGreen.copy(alpha = 0.35f),
                            disabledContentColor = Color.Black.copy(alpha = 0.45f),
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (playing) "Starting…" else "Play",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
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
