package com.bockmedia.console.ui.navigation

import androidx.navigation.NavHostController
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.HomeCardBrowse

fun NavHostController.browseHomeCard(card: HomeCard) {
    when (val dest = HomeCardBrowse.destination(card) ?: return) {
        is HomeCardBrowse.Destination.Playlist ->
            navigate(playlistDetailRoute(dest.id)) { launchSingleTop = true }
        is HomeCardBrowse.Destination.Artist ->
            navigate(albumsArtistRoute(dest.name)) { launchSingleTop = true }
        is HomeCardBrowse.Destination.Album ->
            navigate(songsAlbumRoute(dest.name, dest.artist)) { launchSingleTop = true }
        is HomeCardBrowse.Destination.Genre ->
            navigate(genreRoute(dest.name)) { launchSingleTop = true }
        is HomeCardBrowse.Destination.Downloads ->
            navigate(BockRoute.Downloads.route) { launchSingleTop = true }
        is HomeCardBrowse.Destination.Search ->
            navigate(BockRoute.Search.route) { launchSingleTop = true }
    }
}
