package com.bockmedia.console.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BockRoute(val route: String, val title: String, val icon: ImageVector) {
    data object Home : BockRoute("home", "Home", Icons.Default.Home)
    data object NowPlaying : BockRoute("nowplaying", "Now Playing", Icons.Default.PlayArrow)
    data object Library : BockRoute("library", "Library", Icons.Default.LibraryMusic)
    data object Search : BockRoute("search", "Search", Icons.Default.Search)
    data object Playlists : BockRoute("playlists", "Playlists", Icons.AutoMirrored.Filled.List)
    data object Automations : BockRoute("automations", "Automations", Icons.Default.Schedule)
    data object Albums : BockRoute("albums", "Albums", Icons.Default.Album)
    data object Artists : BockRoute("artists", "Artists", Icons.Default.Mic)
    data object Songs : BockRoute("songs", "Songs", Icons.Default.MusicNote)
    data object Favorites : BockRoute("favorites", "Rated", Icons.Default.Star)
    data object Downloads : BockRoute("downloads", "Downloads", Icons.Default.Download)
    data object Routines : BockRoute("routines", "Routines", Icons.Default.Bolt)
    data object RecentRequests : BockRoute("recent", "Voice log", Icons.Default.RecordVoiceOver)
    data object Rooms : BockRoute("rooms", "Rooms", Icons.Default.Home)
    data object Devices : BockRoute("devices", "Alexa Devices", Icons.Default.Speaker)
    data object Family : BockRoute("family", "Family", Icons.Default.Group)
    data object Driving : BockRoute("driving", "Driving Mode", Icons.Default.DirectionsCar)
    data object Analytics : BockRoute("analytics", "Analytics", Icons.Default.Analytics)
    data object Settings : BockRoute("settings", "Settings", Icons.Default.Settings)
    data object About : BockRoute("about", "About", Icons.Default.Info)

    companion object {
        val bottomNavRoutes: List<BockRoute> by lazy {
            listOf(Home, Search, Library, Downloads, Automations)
        }

        val accountMenuRoutes: List<BockRoute> by lazy {
            listOf(Settings, Downloads, RecentRequests, Rooms, Devices, Family, Analytics, About)
        }

        val allRoutes: List<BockRoute> by lazy {
            bottomNavRoutes + accountMenuRoutes + listOf(
                NowPlaying, Playlists, Artists, Albums, Songs, Favorites,
            )
        }
    }
}

const val ROUTE_PLAYLIST_DETAIL = "playlists/detail/{id}"
const val ROUTE_ALBUMS_ARTIST = "albums/{artist}"
const val ROUTE_SONGS_ARTIST = "songs/artist/{artist}"
const val ROUTE_SONGS_ALBUM = "songs/album/{album}?artist={artist}"
const val ROUTE_GENRE = "genre/{name}"

fun playlistDetailRoute(id: String) = "playlists/detail/$id"
fun albumsArtistRoute(artist: String) = "albums/${java.net.URLEncoder.encode(artist, "UTF-8")}"
fun songsArtistRoute(artist: String) = "songs/artist/${java.net.URLEncoder.encode(artist, "UTF-8")}"
fun songsAlbumRoute(album: String, artist: String? = null): String {
    val encoded = java.net.URLEncoder.encode(album, "UTF-8")
    val art = artist?.trim()?.takeIf { it.isNotEmpty() }
    return if (art != null) {
        "songs/album/$encoded?artist=${java.net.URLEncoder.encode(art, "UTF-8")}"
    } else {
        "songs/album/$encoded"
    }
}
fun genreRoute(name: String) = "genre/${java.net.URLEncoder.encode(name, "UTF-8")}"
