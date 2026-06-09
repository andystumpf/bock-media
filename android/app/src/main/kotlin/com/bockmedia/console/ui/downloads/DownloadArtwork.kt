package com.bockmedia.console.ui.downloads

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.OfflineCollectionManifest
import com.bockmedia.console.ui.components.BockArtwork

@Composable
fun DownloadArtwork(
    repository: BockMediaRepository,
    manifest: OfflineCollectionManifest,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    cornerRadius: Dp = 6.dp,
) {
    val artUrl by produceState<String?>(initialValue = null, manifest.id, manifest.tracks.size) {
        value = repository.resolveOfflineManifestArtUrl(manifest)
    }
    BockArtwork(
        model = artUrl,
        title = manifest.title,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
        shape = RoundedCornerShape(cornerRadius),
        fallbackFontSize = 16.sp,
    )
}
