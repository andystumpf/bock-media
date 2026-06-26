package com.bockmedia.console.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SearchPin
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockLazyColumn
import kotlinx.coroutines.launch

private val PIN_KINDS = listOf("artist", "album", "genre", "playlist", "radio", "mix")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPinsEditorSheet(
    repository: BockMediaRepository,
    initialPins: List<SearchPin>,
    onDismiss: () -> Unit,
    onSaved: (List<SearchPin>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pins by remember { mutableStateOf(initialPins) }
    var kind by remember { mutableStateOf("artist") }
    var title by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var kindExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Edit shortcuts", style = MaterialTheme.typography.titleLarge)
            Text(
                "Custom shortcuts appear below Aural fixations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            BockLazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            ) {
                itemsIndexed(pins, key = { idx, pin -> "$idx-${pin.kind}-${pin.id ?: pin.name}" }) { _, pin ->
                    ListItem(
                        headlineContent = { Text(pin.title ?: pin.name ?: pin.kind) },
                        supportingContent = { Text(pin.kind) },
                        trailingContent = {
                            IconButton(onClick = { pins = pins.filterNot { it == pin } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ExposedDropdownMenuBox(expanded = kindExpanded, onExpandedChange = { kindExpanded = it }) {
                OutlinedTextField(
                    value = kind,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Kind") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                    PIN_KINDS.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k) },
                            onClick = { kind = k; kindExpanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name / ID") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val t = title.trim().ifBlank { name.trim() }
                        if (t.isEmpty()) return@TextButton
                        pins = pins + SearchPin(kind = kind, title = t, name = name.trim().ifBlank { null })
                        title = ""
                        name = ""
                    },
                ) { Text("Add") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            if (repository.saveSearchPins(pins)) onSaved(pins)
                            saving = false
                            onDismiss()
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }
}
