package com.bockmedia.console.ui.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.HouseholdMember
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientIdStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.InstallIdentity
import kotlinx.coroutines.launch

/** Blocks the app until a household profile is chosen (required after reinstall). */
@Composable
fun ProfilePickerGate(
    repository: BockMediaRepository,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<HouseholdMember>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val activeId = ActiveProfileStore.activeMemberId(context)

    LaunchedEffect(Unit) {
        if (!activeId.isNullOrBlank()) return@LaunchedEffect
        members = runCatching { repository.household().members }.getOrDefault(emptyList())
    }

    if (!activeId.isNullOrBlank() || members.isNullOrEmpty()) {
        content()
        return
    }

    if (members!!.size == 1) {
        LaunchedEffect(members) {
            val only = members!!.first().id
            ActiveProfileStore.setActiveMember(context, only)
            runCatching {
                repository.bindClient(
                    ClientIdStore.clientId(context),
                    only,
                    InstallIdentity.phoneId(context),
                )
            }
            ClientPrefsSync.pullAndApply(context)
        }
        content()
        return
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Who's listening?") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Choose your profile so ratings and settings restore from the server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(members!!, key = { it.id }) { member ->
                        ListItem(
                            headlineContent = { Text(member.name) },
                            supportingContent = {
                                Text(if (member.role == "parent") "Parent" else "Kid")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !loading) {
                                    loading = true
                                    scope.launch {
                                        val previous = ActiveProfileStore.activeMemberId(context)
                                        ActiveProfileStore.setActiveMember(context, member.id)
                                        runCatching {
                                            repository.bindClient(
                                                ClientIdStore.clientId(context),
                                                member.id,
                                                InstallIdentity.phoneId(context),
                                            )
                                        }
                                        ClientPrefsSync.onActiveMemberChanged(context, member.id, previous)
                                        loading = false
                                    }
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
