package com.bockmedia.console.ui.routines

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.buildRoutinePhrase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("play my morning music") }
    var shuffle by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching {
            playlists = repository.playlists(limit = 500).items
            selectedId = playlists.firstOrNull()?.id.orEmpty()
        }
        loading = false
    }

    val selectedName = playlists.find { it.id == selectedId }?.name.orEmpty()
    LaunchedEffect(selectedName, shuffle) {
        if (selectedName.isNotBlank()) {
            phrase = buildRoutinePhrase(selectedName, shuffle)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .bockVerticalScroll()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Alexa Routines", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create a voice trigger in the Alexa app, then paste the generated phrase as a Custom action.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
            LoadingBox()
            return@Column
        }
        BockTextField(trigger, { trigger = it }, "Routine trigger phrase (for your notes)")
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            BockTextField(
                selectedName.ifBlank { "Select playlist" },
                {},
                "Playlist",
                readOnly = true,
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded, { expanded = false }) {
                playlists.forEach { pl ->
                    DropdownMenuItem(
                        text = { Text(pl.name) },
                        onClick = { selectedId = pl.id; expanded = false },
                    )
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(shuffle, { shuffle = it })
            Text("Shuffle (mix)")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Generated phrase", style = MaterialTheme.typography.labelLarge)
                Text(phrase, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { copyPhrase(context, phrase) }) { Text("Copy phrase") }
            }
        }
        Text(
            "In Alexa: Routines → When → Voice → \"$trigger\" → Action → Custom → paste phrase above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun copyPhrase(context: Context, phrase: String) {
    val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    mgr.setPrimaryClip(ClipData.newPlainText("routine", phrase))
}
