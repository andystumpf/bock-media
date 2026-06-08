package com.bockmedia.console.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BockRoute(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : BockRoute("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object NowPlaying : BockRoute("nowplaying", "Now Playing", Icons.Default.PlayArrow)
    data object Rooms : BockRoute("rooms", "Rooms", Icons.Default.Home)
    data object Search : BockRoute("search", "Search", Icons.Default.Search)
    data object Playlists : BockRoute("playlists", "Playlists", Icons.AutoMirrored.Filled.List)
    data object Albums : BockRoute("albums", "Albums", Icons.Default.Album)
    data object Artists : BockRoute("artists", "Artists", Icons.Default.Mic)
    data object Songs : BockRoute("songs", "Songs", Icons.Default.MusicNote)
    data object WatchFolders : BockRoute("watchfolders", "Watch Folders", Icons.Default.Folder)
    data object Devices : BockRoute("devices", "Alexa Devices", Icons.Default.Speaker)
    data object Automation : BockRoute("automation", "Automation", Icons.Default.Schedule)
    data object Routines : BockRoute("routines", "Routines", Icons.Default.Bolt)
    data object Analytics : BockRoute("analytics", "Analytics", Icons.Default.Analytics)
    data object Settings : BockRoute("settings", "Settings", Icons.Default.Settings)

    companion object {
        val drawerRoutes = listOf(
            NowPlaying, Dashboard, Rooms,
            Search, Playlists, Albums, Artists, Songs,
            WatchFolders, Devices, Automation, Routines, Analytics, Settings,
        )
    }
}

const val ROUTE_PLAYLIST_DETAIL = "playlists/detail/{id}"
const val ROUTE_ALBUMS_ARTIST = "albums/{artist}"
const val ROUTE_SONGS_ARTIST = "songs/artist/{artist}"
const val ROUTE_SONGS_ALBUM = "songs/album/{album}"

fun playlistDetailRoute(id: String) = "playlists/detail/$id"
fun albumsArtistRoute(artist: String) = "albums/${java.net.URLEncoder.encode(artist, "UTF-8")}"
fun songsArtistRoute(artist: String) = "songs/artist/${java.net.URLEncoder.encode(artist, "UTF-8")}"
fun songsAlbumRoute(album: String) = "songs/album/${java.net.URLEncoder.encode(album, "UTF-8")}"
