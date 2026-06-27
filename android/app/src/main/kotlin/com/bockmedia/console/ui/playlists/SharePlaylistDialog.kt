package com.bockmedia.console.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.HouseholdMember

@Composable
fun SharePlaylistDialog(
    members: List<HouseholdMember>,
    activeMemberId: String?,
    alreadyShared: Set<String>,
    onDismiss: () -> Unit,
    onShare: (List<String>) -> Unit,
) {
    val me = activeMemberId?.takeIf { it.isNotBlank() }
    val choices = members.filter { it.id.isNotBlank() && it.id != me }
    var selected by remember(alreadyShared, choices) {
        mutableStateOf(alreadyShared.intersect(choices.map { it.id }.toSet()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share with…") },
        text = {
            if (choices.isEmpty()) {
                Text("Add household members in Settings to share playlists.")
            } else {
                LazyColumn {
                    items(choices, key = { it.id }) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = member.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + member.id else selected - member.id
                                },
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(member.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    member.role.replaceFirstChar { c -> c.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onShare(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun playlistShareBadge(
    ownerMemberId: String?,
    ownerName: String?,
    visibility: String?,
    sharedWith: List<String>,
    daily: Boolean,
    activeMemberId: String?,
    memberName: (String) -> String?,
): String? {
    if (daily) return "Daily"
    val vis = visibility?.lowercase() ?: "household"
    val me = activeMemberId?.takeIf { it.isNotBlank() }
    if (vis == "shared" && !ownerMemberId.isNullOrBlank() && ownerMemberId != me && !ownerName.isNullOrBlank()) {
        return "From $ownerName"
    }
    if (vis == "private" && ownerMemberId == me) return "Private"
    if (vis == "shared" && ownerMemberId == me && sharedWith.isNotEmpty()) {
        val names = sharedWith.mapNotNull { memberName(it) }
        if (names.isNotEmpty()) return "Shared · ${names.joinToString(", ")}"
        return "Shared"
    }
    return null
}
