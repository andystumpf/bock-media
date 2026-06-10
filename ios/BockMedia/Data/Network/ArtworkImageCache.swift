import UIKit

enum ArtworkImageCache {
    private static let memory = NSCache<NSString, UIImage>()
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
            let (data, _) = try await session.data(from: url)
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
