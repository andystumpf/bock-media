package com.bockmedia.console.ui.routines

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.ui.components.bockVerticalScroll
import com.bockmedia.console.ui.components.BockTextField
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.buildRoutinePhrase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching {
            playlists = repository.playlists(limit = 500).items.map { it.id to it.name }
            if (playlists.isNotEmpty()) selected = playlists.first().second
        }
    }

    LaunchedEffect(selected, shuffle) {
        if (selected.isNotBlank()) phrase = buildRoutinePhrase(selected, shuffle)
    }

    Column(Modifier.fillMaxSize().bockVerticalScroll().padding(16.dp)) {
        Text("Generate an Alexa Routine phrase to copy into the Alexa app.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            BockTextField(
                selected,
                {},
                "Playlist",
                readOnly = true,
                modifier = Modifier.menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            )
            ExposedDropdownMenu(expanded, { expanded = false }) {
                playlists.forEach { (_, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { selected = name; expanded = false })
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(shuffle, { shuffle = it })
            Text("Shuffle (mix)")
        }
        Spacer(Modifier.height(8.dp))
        BockTextField(phrase, {}, "Phrase", readOnly = true)
        Button(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("routine", phrase))
        }) { Text("Copy phrase") }
    }
}
