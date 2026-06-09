package com.bockmedia.console.ui.navigation

import java.net.URLDecoder

data class ScreenHeader(val title: String, val showBack: Boolean)

fun resolveScreenHeader(route: String?): ScreenHeader {
    if (route.isNullOrBlank()) return ScreenHeader("Bock Media", showBack = false)
    return when {
        route.startsWith("playlists/detail/") -> ScreenHeader("Playlist", showBack = true)
        route.startsWith("albums/") && route != BockRoute.Albums.route -> {
            val artist = URLDecoder.decode(route.removePrefix("albums/"), "UTF-8")
            ScreenHeader(artist.ifBlank { "Albums" }, showBack = true)
        }
        route.startsWith("songs/artist/") -> {
            val artist = URLDecoder.decode(route.removePrefix("songs/artist/"), "UTF-8")
            ScreenHeader(artist.ifBlank { "Songs" }, showBack = true)
        }
        route.startsWith("songs/album/") -> {
            val album = URLDecoder.decode(route.removePrefix("songs/album/"), "UTF-8")
            ScreenHeader(album.ifBlank { "Songs" }, showBack = true)
        }
        else -> {
            val top = route.substringBefore("/")
            val title = BockRoute.drawerRoutes.find { it.route == top }?.title ?: "Bock Media"
            ScreenHeader(title, showBack = false)
        }
    }
}

fun isBottomNavRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return true
    val top = route.substringBefore("/")
    return BockRoute.bottomNavRoutes.any { it.route == top }
}
