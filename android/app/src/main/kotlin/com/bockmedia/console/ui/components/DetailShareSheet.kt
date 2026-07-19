package com.bockmedia.console.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bockmedia.console.data.repository.BockMediaRepository

@Composable
fun DetailShareSheet(
    title: String,
    deepLink: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    trackPath: String? = null,
    trackTitle: String? = null,
    onAddToPlaylist: () -> Unit,
    onAddToRoom: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: (String) -> Unit = {},
) {
    val context = LocalContext.current
    PlexampEntitySheet(
        title = title,
        rating = null,
        repository = repository,
        actions = buildList {
            add(
                PlexampSheetAction("Copy link", Icons.Default.Link, onClick = {
                    copyDeepLink(context, deepLink)
                    onCopied("Link copied")
                    onDismiss()
                }),
            )
            if (trackPath != null) {
                add(PlexampSheetAction("Add to playlist", Icons.Default.PlaylistAdd, onClick = {
                    onAddToPlaylist()
                    onDismiss()
                }))
            }
            if (remoteOk && trackPath != null) {
                add(PlexampSheetAction("Play on room…", Icons.Default.Speaker, onClick = {
                    onAddToRoom()
                    onDismiss()
                }))
            }
        },
        onDismiss = onDismiss,
    )
}

fun copyDeepLink(context: Context, link: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Bock Media", link))
}
