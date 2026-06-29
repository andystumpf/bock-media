import Foundation

enum HomeArtworkResolver {
    /// One batch request for all playlist tiles, then tiles resolve locally as they appear.
    static func warmPlaylistCovers(repository: BockMediaRepository, cards: [HomeCard]) async {
        _ = try? await repository.resolveBaseURL()
        let ids = Array(Set(cards.compactMap(\.playlistId)))
        await repository.prefetchPlaylistCoverPaths(ids: ids)
    }

    static func resolveURL(repository: BockMediaRepository, card: HomeCard) async -> URL? {
        if let cached = HomeArtworkCache.url(for: card.id) { return cached }

        if let playlistId = card.playlistId,
           let path = playlistPath(repository: repository, id: playlistId),
           let url = await artworkURL(repository: repository, path: path) {
            HomeArtworkCache.store(cardId: card.id, url: url)
            return url
        }

        if let path = card.artPath, let url = await artworkURL(repository: repository, path: path) {
            HomeArtworkCache.store(cardId: card.id, url: url)
            return url
        }

        switch card.playTarget {
        case .album(let name, let artist):
            if let path = try? await repository.songs(page: 1, limit: 1, search: name, artist: artist, album: name).items.first?.path,
               let url = await artworkURL(repository: repository, path: path) {
                HomeArtworkCache.store(cardId: card.id, url: url)
                return url
            }
        case .artist(let name):
            if let path = try? await repository.songs(page: 1, limit: 8, search: name, artist: name).items.first?.path,
               let url = await artworkURL(repository: repository, path: path) {
                HomeArtworkCache.store(cardId: card.id, url: url)
                return url
            }
        case .radio(_, _, let name, let path):
            if let path, let url = await artworkURL(repository: repository, path: path) {
                HomeArtworkCache.store(cardId: card.id, url: url)
                return url
            }
            if let path = try? await repository.songs(page: 1, limit: 8, search: name, artist: name).items.first?.path,
               let url = await artworkURL(repository: repository, path: path) {
                HomeArtworkCache.store(cardId: card.id, url: url)
                return url
            }
        default:
            break
        }
        return nil
    }

    private static func playlistPath(repository: BockMediaRepository, id: String) -> String? {
        if let cached = HomeArtworkCache.playlistPath(id: id) { return cached }
        return nil
    }

    private static func artworkURL(repository: BockMediaRepository, path: String) async -> URL? {
        guard let urlStr = await repository.artworkURL(for: path) else { return nil }
        return URL(string: urlStr)
    }
}
