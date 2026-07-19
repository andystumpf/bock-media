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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bockmedia.console.ui.testing.BockTestTags
import com.bockmedia.console.data.api.dto.HouseholdMember
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.HouseholdStore
import kotlinx.coroutines.launch

/** Blocks until the user picks a household profile or explicitly continues unattributed. */
@Composable
fun ProfilePickerGate(
    repository: BockMediaRepository,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<HouseholdMember>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val choiceMade by ActiveProfileStore.profileChoiceMadeState.collectAsState()
    val householdRevision by HouseholdStore.revision.collectAsState()

    LaunchedEffect(choiceMade, householdRevision) {
        if (choiceMade) return@LaunchedEffect
        val cached = HouseholdStore.members()
        if (cached.isNotEmpty()) {
            members = cached
            return@LaunchedEffect
        }
        members = runCatching { repository.household().members }.getOrDefault(emptyList())
    }

    if (choiceMade) {
        content()
        return
    }

    when (members) {
        null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        emptyList<HouseholdMember>() -> content()
        else -> {
            AlertDialog(
                onDismissRequest = {},
                modifier = Modifier.testTag(BockTestTags.PROFILE_PICKER),
                title = { Text("Who's listening?") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Pick a profile to restore your ratings and settings, " +
                                "or continue unattributed until you choose later in Family.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (loading) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
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
                                                try {
                                                    ClientPrefsSync.onActiveMemberChanged(
                                                        context,
                                                        member.id,
                                                        ActiveProfileStore.activeMemberId(context),
                                                    )
                                                } finally {
                                                    loading = false
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    ClientPrefsSync.onActiveMemberChanged(
                                        context,
                                        null,
                                        ActiveProfileStore.activeMemberId(context),
                                    )
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Text("Continue unattributed")
                    }
                },
            )
        }
    }
}
