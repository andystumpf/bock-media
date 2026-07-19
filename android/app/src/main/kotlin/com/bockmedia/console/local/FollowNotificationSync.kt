package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository

/** Check for unread followed-artist releases and post a local notification. */
object FollowNotificationSync {
    suspend fun checkAndNotify(context: Context, repository: BockMediaRepository) {
        val memberId = ActiveProfileStore.activeMemberId(context)
        val after = FollowNotificationStore.lastSeenAt(context, memberId)
        val response = runCatching {
            repository.followedNotifications(since = "30d", after = after)
        }.getOrNull() ?: return
        if (response.unreadCount <= 0) return
        FollowNotificationHelper.maybeNotify(context, response)
        val timestamps = response.albums.map { it.firstSeenAt } + response.tracks.map { it.firstSeenAt }
        FollowNotificationStore.markSeenFromItems(context, memberId, timestamps)
    }
}
