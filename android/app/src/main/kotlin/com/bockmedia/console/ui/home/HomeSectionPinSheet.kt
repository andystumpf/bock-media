package com.bockmedia.console.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bockmedia.console.domain.model.HomePinTargets
import com.bockmedia.console.local.HomeSectionPinsStore
import com.bockmedia.console.ui.theme.BockGreen

private val SheetBg = Color(0xFF282828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionPinSheet(
    playlistId: String,
    playlistName: String,
    suggestedSectionId: String? = null,
    onDismiss: () -> Unit,
    onPinned: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pinnedSections by remember(playlistId) {
        mutableStateOf(HomeSectionPinsStore.pinnedSections(playlistId).toSet())
    }
    val targets = remember { HomePinTargets.pinEligible() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Add to Home",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                playlistName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Text(
                "Pin to the beginning of a home row",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(targets, key = { it.sectionId }) { target ->
                    val isPinned = target.sectionId in pinnedSections
                    val isSuggested = target.sectionId == suggestedSectionId
                    ListItem(
                        headlineContent = {
                            Text(
                                target.title,
                                color = Color.White,
                            )
                        },
                        supportingContent = when {
                            isPinned -> {
                                { Text("Pinned · tap to update", color = BockGreen) }
                            }
                            isSuggested -> {
                                { Text("Suggested", color = Color.White.copy(alpha = 0.5f)) }
                            }
                            else -> null
                        },
                        trailingContent = if (isPinned) {
                            { Text("✓", color = BockGreen) }
                        } else null,
                        modifier = Modifier.clickable {
                            HomeSectionPinsStore.pin(
                                context,
                                target.sectionId,
                                playlistId,
                                playlistName,
                            )
                            pinnedSections = pinnedSections + target.sectionId
                            onPinned(target.title)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)) {
                Text("Not now")
            }
        }
    }
}
