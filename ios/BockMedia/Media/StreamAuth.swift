import AVFoundation
import Foundation

/// Builds AVPlayer items with the same auth headers API clients use (AVPlayer cannot set headers per-request otherwise).
enum StreamAuth {
    static func playerItem(url: URL, preferences: AppPreferences?) -> AVPlayerItem {
        if isDirectCdn(url: url) {
            return AVPlayerItem(url: url)
        }
        guard let preferences else { return AVPlayerItem(url: url) }
        let headers = AuthHeaders.headerFields(
            username: preferences.adminUser,
            password: preferences.adminPass,
            token: preferences.mobileToken
        )
        guard !headers.isEmpty else { return AVPlayerItem(url: url) }
        let asset = AVURLAsset(
            url: url,
            options: ["AVURLAssetHTTPHeaderFieldsKey": headers]
        )
        return AVPlayerItem(asset: asset)
    }

    private static func isDirectCdn(url: URL) -> Bool {
        let host = url.host?.lowercased() ?? ""
        let path = url.path.lowercased()
        if path.contains("/api/music-video/") && path.contains("/proxy") { return false }
        return host.contains("googlevideo.com") ||
            host.contains("youtube.com") ||
            host.contains("pipedproxy") ||
            host.contains("piped.video")
    }
}
