package com.bockmedia.console.ui.nowplaying

/** True for direct YouTube/Piped CDN URLs (not NAS /api/music-video/.../proxy). */
fun isMusicVideoDirectCdnUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("http://") && !u.startsWith("https://")) return false
    if (u.contains("/api/music-video/") && u.contains("/proxy")) return false
    return u.contains("googlevideo.com") ||
        u.contains("youtube.com/videoplayback") ||
        u.contains("pipedproxy") ||
        u.contains("piped.video")
}
