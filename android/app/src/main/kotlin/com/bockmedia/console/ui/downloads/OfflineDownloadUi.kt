package com.bockmedia.console.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.OfflineCollectionStatus
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadSync

/** One shared snapshot — tiles read this instead of each collecting the full flow. */
val LocalVisibleDownloadStatuses = compositionLocalOf<Map<String, OfflineCollectionStatus>> {
    emptyMap()
}

@Composable
fun VisibleDownloadStatusesProvider(content: @Composable () -> Unit) {
    val statuses = rememberVisibleDownloadStatuses()
    CompositionLocalProvider(LocalVisibleDownloadStatuses provides statuses) {
        content()
    }
}

@Composable
fun rememberVisibleDownloadStatuses(): Map<String, OfflineCollectionStatus> {
    val context = LocalContext.current
    val all by OfflineDownloadManager.statuses.collectAsState()
    val memberId by ActiveProfileStore.activeMemberIdState.collectAsState()
    val profileRevision by ClientPrefsSync.profileChangeRevision.collectAsState()
    return remember(all, memberId, profileRevision) {
        val ids = OfflineDownloadSync.visibleCollectionIds(context)
        all.filterKeys { it in ids }
    }
}
