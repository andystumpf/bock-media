package com.bockmedia.console.ui.navigation

import androidx.navigation.NavBackStackEntry
import java.net.URLDecoder

data class ScreenHeader(val title: String, val showBack: Boolean)

fun resolveScreenHeader(entry: NavBackStackEntry?): ScreenHeader {
    val route = entry?.destination?.route
    val args = entry?.arguments
    return resolveScreenHeader(
        route = route,
        artist = args?.getString("artist"),
        album = args?.getString("album"),
    )
}

internal fun resolveScreenHeader(
    route: String?,
    artist: String? = null,
    album: String? = null,
): ScreenHeader {
    if (route.isNullOrBlank()) return ScreenHeader("Bock Media", showBack = false)
    return when (route) {
        ROUTE_PLAYLIST_DETAIL -> ScreenHeader("Playlist", showBack = true)
        ROUTE_ALBUMS_ARTIST -> {
            val name = decodeNavArg(artist)
            ScreenHeader(name.ifBlank { "Albums" }, showBack = true)
        }
        ROUTE_SONGS_ARTIST -> {
            val name = decodeNavArg(artist)
            ScreenHeader(name.ifBlank { "Songs" }, showBack = true)
        }
        ROUTE_SONGS_ALBUM -> {
            val name = decodeNavArg(album)
            ScreenHeader(name.ifBlank { "Songs" }, showBack = true)
        }
        else -> when {
            route.startsWith("playlists/detail/") -> ScreenHeader("Playlist", showBack = true)
            route.startsWith("albums/") && route != BockRoute.Albums.route -> {
                val name = decodeNavArg(route.removePrefix("albums/"))
                ScreenHeader(name.ifBlank { "Albums" }, showBack = true)
            }
            route.startsWith("songs/artist/") -> {
                val name = decodeNavArg(route.removePrefix("songs/artist/"))
                ScreenHeader(name.ifBlank { "Songs" }, showBack = true)
            }
            route.startsWith("songs/album/") -> {
                val name = decodeNavArg(route.removePrefix("songs/album/"))
                ScreenHeader(name.ifBlank { "Songs" }, showBack = true)
            }
            else -> {
                val top = route.substringBefore("/")
                val bottomNavTops = BockRoute.bottomNavRoutes.map { it.route }
                val showBack = top !in bottomNavTops
                val title = if (top in bottomNavTops) {
                    ""
                } else {
                    BockRoute.allRoutes.find { it.route == top }?.title ?: "Bock Media"
                }
                ScreenHeader(title, showBack = showBack)
            }
        }
    }
}

private fun decodeNavArg(value: String?): String {
    if (value.isNullOrBlank() || value.startsWith("{")) return ""
    return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

fun isBottomNavRoute(route: String?): Boolean {
    if (route.isNullOrBlank()) return true
    val top = route.substringBefore("/")
    return BockRoute.bottomNavRoutes.any { it.route == top }
}
