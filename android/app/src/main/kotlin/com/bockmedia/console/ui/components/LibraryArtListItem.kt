package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository

@Composable
fun LibraryArtListItem(
    repository: BockMediaRepository,
    title: String,
    subtitle: String,
    artPath: String? = null,
    artistName: String? = null,
    albumName: String? = null,
    showUnplayed: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = artPath,
        artistName = artistName,
        albumName = albumName,
        albumArtist = artistName,
    )

    ListItem(
        modifier = modifier,
        leadingContent = {
            ArtworkWithUnplayedBadge(showUnplayed = showUnplayed) {
                BockArtwork(
                    model = artUrl,
                    title = title,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(6.dp),
                    fallbackFontSize = 16.sp,
                )
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = trailing,
    )
}
