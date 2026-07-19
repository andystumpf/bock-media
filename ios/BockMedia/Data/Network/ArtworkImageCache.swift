import UIKit

enum ArtworkImageCache {
    private static let memory = NSCache<NSString, UIImage>()
    private static weak var preferences: AppPreferences?
    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(
            memoryCapacity: 64_000_000,
            diskCapacity: 256_000_000,
            diskPath: "bock_artwork"
        )
        config.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: config)
    }()

    static func configure(preferences: AppPreferences) {
        self.preferences = preferences
    }

    static func configureSharedURLCache() {
        URLCache.shared = URLCache(
            memoryCapacity: 64_000_000,
            diskCapacity: 256_000_000,
            diskPath: "bock_artwork_shared"
        )
    }

    static func memoryImage(for url: URL) -> UIImage? {
        memory.object(forKey: url.absoluteString as NSString)
    }

    static func load(_ url: URL) async -> UIImage? {
        let key = url.absoluteString as NSString
        if let hit = memory.object(forKey: key) { return hit }
        do {
            var request = URLRequest(url: url)
            if let preferences {
                AuthHeaders.apply(
                    to: &request,
                    localHosts: preferences.localHosts(),
                    username: preferences.adminUser,
                    password: preferences.adminPass,
                    token: preferences.mobileToken
                )
            }
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { return nil }
            guard let image = UIImage(data: data) else { return nil }
            memory.setObject(image, forKey: key)
            return image
        } catch {
            return nil
        }
    }

    static func prefetch(_ url: URL) async {
        _ = await load(url)
    }
}
